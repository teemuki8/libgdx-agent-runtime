package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded application-owned checkpoint creation, restore, and retention. */
public final class CheckpointRegistry {
    private final AgentRuntime runtime;
    private final CheckpointLimits limits;
    private final LinkedHashMap<String, Retained> checkpoints = new LinkedHashMap<>();
    private final LinkedHashMap<String, CheckpointDescriptor> catalog = new LinkedHashMap<>();
    private final LinkedHashMap<String, Request> requests = new LinkedHashMap<>();
    private final LinkedHashMap<String, Evidence> operations = new LinkedHashMap<>();
    private CheckpointProvider provider;

    CheckpointRegistry(AgentRuntime runtime, CheckpointLimits limits) {
        this.runtime = runtime;
        this.limits = limits;
    }

    /** Registers the single application-owned checkpoint provider before runtime start. */
    public synchronized void register(CheckpointProvider value) {
        runtime.requireCheckpointRegistration();
        Objects.requireNonNull(value, "provider");
        if (provider != null) {
            throw new IllegalStateException("checkpoint provider is already registered");
        }
        provider = value;
    }

    /** Returns whether application checkpoint callbacks are registered. */
    public synchronized boolean available() {
        return provider != null;
    }

    /** Lists retained descriptors in creation order without exposing opaque handles. */
    public synchronized List<CheckpointDescriptor> list() {
        return List.copyOf(catalog.values());
    }

    /** Creates one checkpoint from the current quiescent application state. */
    public CheckpointOperation create(String checkpointId, String description, String requestId,
            Duration timeout) {
        IdentifierSupport.validate(checkpointId, "checkpoint id");
        IdentifierSupport.validate(requestId, "request id");
        Optional<String> boundedDescription = Optional.ofNullable(description);
        boundedDescription.ifPresent(value -> {
            if (value.isBlank() || value.length() > limits.descriptionLength()) {
                throw new IllegalArgumentException("checkpoint description is invalid");
            }
        });
        return submit(new Request(CheckpointOperation.Kind.CREATE, checkpointId,
                boundedDescription), requestId, timeout);
    }

    /** Restores one retained checkpoint and captures a new epoch baseline on success. */
    public CheckpointOperation restore(String checkpointId, String requestId, Duration timeout) {
        IdentifierSupport.validate(checkpointId, "checkpoint id");
        IdentifierSupport.validate(requestId, "request id");
        return submit(new Request(CheckpointOperation.Kind.RESTORE, checkpointId,
                Optional.empty()), requestId, timeout);
    }

    /** Returns configured effective limits. */
    public CheckpointLimits limits() {
        return limits;
    }

    private CheckpointOperation submit(Request request, String requestId, Duration timeout) {
        runtime.requireSubmissionsOpen();
        CommandDispatch dispatch = runtime.commands().orElseThrow(() ->
                new IllegalStateException("checkpoint mutation requires application command dispatch"));
        requireValidTimeout(timeout, dispatch.limits().maximumTimeoutNanos());
        synchronized (this) {
            runtime.requireSubmissionsOpen();
            requireProvider();
            Request previous = requests.get(requestId);
            if (previous != null && !previous.equals(request)) {
                throw new IllegalArgumentException(
                        "request id is already bound to another checkpoint operation");
            }
            if (previous != null) {
                return snapshot(requestId, dispatch.status(requestId));
            }
            if (dispatch.status(requestId).kind() != CommandLookup.Kind.UNKNOWN) {
                throw new IllegalArgumentException(
                        "request id is no longer correlated with retained checkpoint evidence");
            }
            if (request.kind() == CheckpointOperation.Kind.CREATE
                    && (checkpoints.containsKey(request.checkpointId())
                            || requests.values().stream().anyMatch(existing ->
                                    existing.kind() == CheckpointOperation.Kind.CREATE
                                            && existing.checkpointId()
                                                    .equals(request.checkpointId())))) {
                throw new IllegalArgumentException(
                        "checkpoint id is already retained or pending");
            }
            if (request.kind() == CheckpointOperation.Kind.RESTORE
                    && !checkpoints.containsKey(request.checkpointId())) {
                throw new IllegalArgumentException("unknown checkpoint id");
            }
            ensureOperationCapacity(dispatch);
            requests.put(requestId, request);
            operations.put(requestId, new Evidence(request));
            // Capacity was established before accepting this request.
        }
        CommandLookup lookup;
        try {
            lookup = dispatch.submit(requestId, timeout, () -> execute(requestId));
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                requests.remove(requestId);
                operations.remove(requestId);
            }
            throw failure;
        }
        return snapshot(requestId, lookup);
    }

    private void execute(String requestId) {
        Evidence evidence;
        CheckpointProvider callbacks;
        synchronized (this) {
            evidence = operations.get(requestId);
            callbacks = requireProvider();
        }
        if (evidence.request.kind() == CheckpointOperation.Kind.CREATE) {
            executeCreate(requestId, evidence, callbacks);
        } else {
            executeRestore(requestId, evidence, callbacks);
        }
    }

    private void executeCreate(String requestId, Evidence evidence, CheckpointProvider callbacks) {
        try {
            runtime.requireCheckpointMutation();
            synchronized (this) {
                if (checkpoints.size() >= limits.retainedCheckpoints()) {
                    String oldest = checkpoints.keySet().iterator().next();
                    Retained evicted = checkpoints.remove(oldest);
                    catalog.remove(oldest);
                    callbacks.dispose(evicted.handle());
                }
            }
            FrameSnapshot source = runtime.latestFrame().orElseThrow(() ->
                    new IllegalStateException("checkpoint creation requires a completed frame"));
            CheckpointHandle handle = Objects.requireNonNull(callbacks.create(),
                    "checkpoint provider returned a null handle");
            CheckpointDescriptor descriptor = new CheckpointDescriptor(
                    evidence.request.checkpointId(), source.executionEpochId(), source.frameId(),
                    evidence.request.description(), runtime.wallTime(), requestId);
            synchronized (this) {
                checkpoints.put(descriptor.id(), new Retained(descriptor, handle));
                catalog.put(descriptor.id(), descriptor);
                evidence.descriptor = descriptor;
            }
        } catch (RuntimeException | Error failure) {
            ApplicationFailureEvidence failureEvidence = describeFailure(
                    requestId, "checkpoint.create", failure);
            synchronized (this) {
                evidence.diagnostic = failureEvidence.legacyEnvelope();
                evidence.applicationFailure = Optional.of(failureEvidence);
            }
            throw failure;
        }
    }

    private void executeRestore(
            String requestId, Evidence evidence, CheckpointProvider callbacks) {
        Retained retained;
        synchronized (this) {
            retained = checkpoints.get(evidence.request.checkpointId());
            if (retained == null) {
                throw new IllegalStateException("checkpoint was evicted before restore execution");
            }
            evidence.descriptor = retained.descriptor();
            evidence.partial = true;
        }
        try {
            runtime.requireCheckpointMutation();
            callbacks.restore(retained.handle());
            FrameId baseline = runtime.startEpoch(BaselineKind.CHECKPOINT_RESTORE);
            synchronized (this) {
                evidence.baselineEpoch = runtime.currentEpoch();
                evidence.baselineFrame = baseline;
                evidence.partial = false;
            }
        } catch (RuntimeException | Error failure) {
            ApplicationFailureEvidence failureEvidence = describeFailure(
                    requestId, "checkpoint.restore", failure);
            synchronized (this) {
                evidence.diagnostic = failureEvidence.legacyEnvelope();
                evidence.applicationFailure = Optional.of(failureEvidence);
            }
            throw failure;
        }
    }

    private CheckpointOperation snapshot(String requestId, CommandLookup command) {
        synchronized (this) {
            Evidence evidence = operations.get(requestId);
            if (evidence == null) {
                throw new IllegalArgumentException("checkpoint operation evidence was evicted");
            }
            return new CheckpointOperation(evidence.request.kind(),
                    evidence.request.checkpointId(), requestId, command,
                    Optional.ofNullable(evidence.descriptor),
                    Optional.ofNullable(evidence.baselineEpoch),
                    Optional.ofNullable(evidence.baselineFrame), evidence.partial,
                    Optional.ofNullable(evidence.diagnostic), evidence.applicationFailure);
        }
    }

    synchronized void close() {
        Throwable firstFailure = null;
        if (provider != null) {
            for (Retained retained : checkpoints.values()) {
                try {
                    provider.dispose(retained.handle());
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else {
                        firstFailure.addSuppressed(failure);
                    }
                }
            }
        }
        checkpoints.clear();
        requests.clear();
        operations.clear();
        provider = null;
        if (firstFailure instanceof RuntimeException failure) {
            throw failure;
        }
        if (firstFailure instanceof Error failure) {
            throw failure;
        }
    }

    /** Package-private close observation: number of retained pending checkpoint handles. */
    synchronized int retainedCheckpoints() {
        return checkpoints.size();
    }

    /** Package-private close observation: number of retained pending checkpoint operations. */
    synchronized int retainedOperations() {
        return operations.size();
    }

    private CheckpointProvider requireProvider() {
        if (provider == null) {
            throw new IllegalStateException("checkpoint provider is not registered");
        }
        return provider;
    }

    private void ensureOperationCapacity(CommandDispatch dispatch) {
        while (operations.size() >= limits.retainedOperations()) {
            String oldest = operations.keySet().iterator().next();
            CommandLookup lookup = dispatch.status(oldest);
            if (lookup.kind() == CommandLookup.Kind.FOUND
                    && lookup.status().map(status -> !isTerminal(status.state())).orElse(false)) {
                throw new AgentRuntimeException(RuntimeErrorCode.LIMIT_EXCEEDED,
                        "checkpoint operation evidence limit reached");
            }
            operations.remove(oldest);
            requests.remove(oldest);
        }
    }

    private static boolean isTerminal(CommandState state) {
        return switch (state) {
            case REJECTED, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> true;
            case QUEUED, EXECUTING -> false;
        };
    }

    private ApplicationFailureEvidence describeFailure(
            String requestId, String category, Throwable failure) {
        Optional<String> correlationId =
                runtime.commands().orElseThrow().correlationId(requestId);
        return correlationId.isPresent()
                ? runtime.diagnostics().describe(category, failure, correlationId.orElseThrow())
                : runtime.diagnostics().describe(category, failure);
    }

    private static void requireValidTimeout(Duration timeout, long maximumNanos) {
        Objects.requireNonNull(timeout, "timeout");
        final long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        if (nanos <= 0 || nanos > maximumNanos) {
            throw new IllegalArgumentException("timeout is outside the configured range");
        }
    }

    private record Request(CheckpointOperation.Kind kind, String checkpointId,
            Optional<String> description) {}
    private record Retained(CheckpointDescriptor descriptor, CheckpointHandle handle) {}

    private static final class Evidence {
        private final Request request;
        private CheckpointDescriptor descriptor;
        private ExecutionEpochId baselineEpoch;
        private FrameId baselineFrame;
        private boolean partial;
        private String diagnostic;
        private Optional<ApplicationFailureEvidence> applicationFailure = Optional.empty();

        private Evidence(Request request) {
            this.request = request;
        }
    }
}
