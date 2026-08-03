package io.github.teemuki8.libgdx.agent.runtime.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe, bounded coordinator for commands executed by an application-owned dispatcher.
 *
 * <p>Only closed runtime features should expose submissions remotely. This local surface accepts a
 * task so feature implementations can share request correlation, deduplication, cancellation, and
 * diagnostics without handing the dispatcher itself to transports.
 */
public final class CommandDispatch {
    private final ApplicationCommandDispatcher dispatcher;
    private final CommandDispatchLimits limits;
    private final MonotonicClock clock;
    private final Thread captureThread;
    private final Map<String, Entry> retained = new LinkedHashMap<>();
    private final ArrayDeque<Entry> pendingDispatch = new ArrayDeque<>();
    private final ArrayDeque<String> terminalOrder = new ArrayDeque<>();
    private final ArrayDeque<String> expiredOrder = new ArrayDeque<>();
    private final Map<String, Boolean> expired = new LinkedHashMap<>();
    private int outstandingDispatches;
    private boolean scheduling;
    private boolean closed;

    CommandDispatch(ApplicationCommandDispatcher dispatcher, CommandDispatchLimits limits,
            MonotonicClock clock, Thread captureThread) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.captureThread = Objects.requireNonNull(captureThread, "captureThread");
    }

    /** Returns the configured hard bounds. */
    public CommandDispatchLimits limits() {
        return limits;
    }

    /**
     * Submits one at-most-once command with a relative deadline.
     *
     * <p>A duplicate retained or expired request ID is never dispatched again.
     */
    public CommandLookup submit(String requestId, Duration timeout, Runnable command) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        requireBoundedTimeout(timeoutNanos);
        long now = now();
        long deadline;
        try {
            deadline = Math.addExact(now, timeoutNanos);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeout exceeds the supported range", failure);
        }
        return submit(requestId, deadline, command);
    }

    /** Submits one at-most-once command with an absolute monotonic deadline. */
    public CommandLookup submit(String requestId, long deadlineNanos, Runnable command) {
        IdentifierSupport.validate(requestId, "requestId");
        Objects.requireNonNull(command, "command");
        long now = now();
        if (deadlineNanos < 0) {
            throw new IllegalArgumentException("deadlineNanos must be non-negative");
        }
        if (deadlineNanos > now) {
            requireBoundedTimeout(deadlineNanos - now);
        }
        Entry entry;
        synchronized (this) {
            CommandLookup duplicate = lookupWithoutExpiry(requestId, now);
            if (duplicate.kind() != CommandLookup.Kind.UNKNOWN) {
                return duplicate;
            }
            if (closed) {
                entry = terminal(requestId, CommandState.REJECTED, now, deadline(now, deadlineNanos),
                        true, "command dispatch is closed");
                return CommandLookup.found(snapshot(entry, now));
            }
            if (deadlineNanos < now) {
                entry = terminal(requestId, CommandState.TIMED_OUT, now, now, true,
                        "deadline elapsed before queueing");
                return CommandLookup.found(snapshot(entry, now));
            }
            if (outstandingDispatches >= limits.queuedCommands()) {
                entry = terminal(requestId, CommandState.REJECTED, now, deadlineNanos, true,
                        "command queue limit reached");
                return CommandLookup.found(snapshot(entry, now));
            }
            entry = new Entry(requestId, now, deadlineNanos, command);
            retained.put(requestId, entry);
            pendingDispatch.addLast(entry);
            outstandingDispatches++;
        }
        schedulePending();
        return status(requestId);
    }

    /** Reads one status without blocking application execution. */
    public synchronized CommandLookup status(String requestId) {
        IdentifierSupport.validate(requestId, "requestId");
        return lookupWithoutExpiry(requestId, now());
    }

    /** Cancels only a command that has not begun application-thread execution. */
    public synchronized CommandCancellation cancel(String requestId) {
        IdentifierSupport.validate(requestId, "requestId");
        long now = now();
        CommandLookup lookup = lookupWithoutExpiry(requestId, now);
        if (lookup.kind() != CommandLookup.Kind.FOUND) {
            return new CommandCancellation(false, lookup);
        }
        Entry entry = retained.get(requestId);
        if (entry.state != CommandState.QUEUED) {
            return new CommandCancellation(false, lookup);
        }
        finish(entry, CommandState.CANCELLED, now, true, "cancelled before dispatch");
        return new CommandCancellation(true, CommandLookup.found(snapshot(entry, now)));
    }

    synchronized void close() {
        closed = true;
        long now = now();
        retained.values().stream()
                .filter(entry -> entry.state == CommandState.QUEUED)
                .toList()
                .forEach(entry -> {
                    finish(entry, CommandState.REJECTED, now, true,
                            "runtime closed before dispatch");
                });
    }

    private void execute(Entry entry) {
        Runnable command;
        synchronized (this) {
            if (entry.dispatchConsumed) {
                return;
            }
            entry.dispatchConsumed = true;
            outstandingDispatches--;
            if (entry.state != CommandState.QUEUED) {
                return;
            }
            long now = now();
            if (now >= entry.deadlineNanos) {
                finish(entry, CommandState.TIMED_OUT, now, true,
                        "deadline elapsed before dispatch");
                return;
            }
            if (Thread.currentThread() != captureThread) {
                finish(entry, CommandState.FAILED, now, true,
                        "dispatcher executed command outside the capture thread");
                return;
            }
            entry.state = CommandState.EXECUTING;
            entry.startedAtNanos = now;
            command = entry.command;
        }
        try {
            command.run();
            synchronized (this) {
                finish(entry, CommandState.SUCCEEDED, now(), true, null);
            }
        } catch (Throwable failure) {
            synchronized (this) {
                finish(entry, CommandState.FAILED, now(), true,
                        diagnostic("command failed", failure));
            }
        }
    }

    private void schedulePending() {
        synchronized (this) {
            if (scheduling) {
                return;
            }
            scheduling = true;
        }
        while (true) {
            Entry entry;
            synchronized (this) {
                entry = pendingDispatch.pollFirst();
                if (entry == null) {
                    scheduling = false;
                    return;
                }
                if (entry.state != CommandState.QUEUED) {
                    consumeDispatch(entry);
                    continue;
                }
            }
            try {
                dispatcher.dispatch(() -> execute(entry));
            } catch (RuntimeException failure) {
                synchronized (this) {
                    consumeDispatch(entry);
                    if (entry.state == CommandState.QUEUED) {
                        finish(entry, CommandState.REJECTED, now(), true,
                                diagnostic("dispatcher rejected command", failure));
                    }
                }
            } catch (Error failure) {
                synchronized (this) {
                    consumeDispatch(entry);
                    if (entry.state == CommandState.QUEUED) {
                        finish(entry, CommandState.FAILED, now(), true,
                                diagnostic("dispatcher failed", failure));
                    }
                    Entry pending;
                    while ((pending = pendingDispatch.pollFirst()) != null) {
                        consumeDispatch(pending);
                        if (pending.state == CommandState.QUEUED) {
                            finish(pending, CommandState.REJECTED, now(), true,
                                    "dispatcher unavailable after failure");
                        }
                    }
                    scheduling = false;
                }
                throw failure;
            }
        }
    }

    private void consumeDispatch(Entry entry) {
        if (!entry.dispatchConsumed) {
            entry.dispatchConsumed = true;
            outstandingDispatches--;
        }
    }

    private CommandLookup lookupWithoutExpiry(String requestId, long now) {
        Entry entry = retained.get(requestId);
        if (entry != null) {
            if (entry.state == CommandState.QUEUED && now >= entry.deadlineNanos) {
                finish(entry, CommandState.TIMED_OUT, now, true,
                        "deadline elapsed before dispatch");
            }
            return CommandLookup.found(snapshot(entry, now));
        }
        return CommandLookup.missing(expired.containsKey(requestId)
                ? CommandLookup.Kind.EXPIRED : CommandLookup.Kind.UNKNOWN);
    }

    private CommandStatus snapshot(Entry entry, long now) {
        if (entry.state == CommandState.EXECUTING && now >= entry.deadlineNanos) {
            return new CommandStatus(entry.requestId, CommandState.TIMED_OUT,
                    entry.submittedAtNanos, entry.deadlineNanos,
                    Optional.of(entry.startedAtNanos), Optional.empty(), false,
                    Optional.of("deadline elapsed after dispatch; outcome is not yet known"));
        }
        return new CommandStatus(entry.requestId, entry.state, entry.submittedAtNanos,
                entry.deadlineNanos, Optional.ofNullable(entry.startedAtNanos),
                Optional.ofNullable(entry.completedAtNanos), entry.outcomeKnown,
                Optional.ofNullable(entry.diagnostic));
    }

    private Entry terminal(String id, CommandState state, long now, long deadline,
            boolean outcomeKnown, String diagnostic) {
        Entry entry = new Entry(id, now, deadline, () -> {});
        retained.put(id, entry);
        finish(entry, state, now, outcomeKnown, diagnostic);
        return entry;
    }

    private void finish(Entry entry, CommandState state, long now,
            boolean outcomeKnown, String diagnostic) {
        entry.state = state;
        entry.completedAtNanos = now;
        entry.outcomeKnown = outcomeKnown;
        entry.diagnostic = limit(diagnostic);
        entry.command = null;
        terminalOrder.addLast(entry.requestId);
        while (terminalOrder.size() > limits.retainedResults()) {
            String removed = terminalOrder.removeFirst();
            Entry value = retained.get(removed);
            if (value != null && terminal(value.state)) {
                retained.remove(removed);
                rememberExpired(removed);
            }
        }
    }

    private void rememberExpired(String requestId) {
        expired.put(requestId, Boolean.TRUE);
        expiredOrder.addLast(requestId);
        while (expiredOrder.size() > limits.retainedRequestIds()) {
            expired.remove(expiredOrder.removeFirst());
        }
    }

    private String diagnostic(String prefix, Throwable failure) {
        String message = failure.getMessage();
        String detail = message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
        return limit(prefix + ": " + detail);
    }

    private String limit(String value) {
        if (value == null || value.length() <= limits.diagnosticLength()) {
            return value;
        }
        return value.substring(0, limits.diagnosticLength());
    }

    private long now() {
        long value = clock.nanoTime();
        if (value < 0) {
            throw new IllegalStateException("monotonic clock returned a negative value");
        }
        return value;
    }

    private void requireBoundedTimeout(long timeoutNanos) {
        if (timeoutNanos > limits.maximumTimeoutNanos()) {
            throw new IllegalArgumentException("timeout exceeds configured maximum of "
                    + limits.maximumTimeoutNanos() + " nanoseconds");
        }
    }

    private static long deadline(long now, long requested) {
        return Math.max(now, requested);
    }

    private static boolean terminal(CommandState state) {
        return state != CommandState.QUEUED && state != CommandState.EXECUTING;
    }

    private static final class Entry {
        private final String requestId;
        private final long submittedAtNanos;
        private final long deadlineNanos;
        private Runnable command;
        private CommandState state = CommandState.QUEUED;
        private Long startedAtNanos;
        private Long completedAtNanos;
        private boolean outcomeKnown;
        private boolean dispatchConsumed;
        private String diagnostic;

        Entry(String requestId, long submittedAtNanos, long deadlineNanos, Runnable command) {
            this.requestId = requestId;
            this.submittedAtNanos = submittedAtNanos;
            this.deadlineNanos = deadlineNanos;
            this.command = command;
        }
    }
}
