# ADR 0012: Application-reported simulation timeline

- Status: Accepted
- Date: 2026-08-09

## Context

Runtime capture frames describe observations, while render frames belong to the application and
controlled ticks currently count only successful `runtime_advance` callbacks. None of those facts
proves that an application executed one fixed simulation step. Treating `FrameSnapshot.deltaNanos`
or the caller-selected `runtime_advance.deltaNanos` as executed simulation time would create false
determinism evidence.

Box2D and other stateful simulations need a session-monotonic tick identity, an epoch-relative
timeline, explicit tick-to-frame correlation, and honest evidence when the application executes a
different delta or a callback/capture fails.

## Decision

`runtime-core` adds a JDK-only `SimulationTimelineRegistry` owned by `AgentRuntime`. The registry
retains separately typed, immutable, bounded `SimulationTick` evidence; simulation metadata is not
added to `FrameSnapshot`.

Application code may register an optional fixed step before `start()` and advances normal running
simulation through one explicit boundary. Its callback returns the delta it actually executed.
The boundary executes any applicable registered input before the callback, captures at most one
runtime frame after it, records completed evidence, and retains an attempted tick even when the
callback or capture fails. Existing controlled and determinism paths delegate to the same boundary.
The existing `SimulationControlRegistry.currentTick()` remains the count of successfully completed
controlled ticks and existing `runtime_advance` request/response semantics remain unchanged.

Legacy `SimulationControllerSpec.tick(LongConsumer)` callbacks do not acknowledge an executed
delta. A new explicitly named callback form returns the application-reported executed delta.
Neither a supplied delta nor a configured fixed step is inferred to have been executed. A mismatch
is retained as a closed typed outcome and never reported as a successful fixed-step match.

Each attempted tick receives a never-reused session `SimulationTickId` and an `epochTick` beginning
at one. Epoch baselines remain frames, not ticks, and reset epoch simulation time to zero. Completed
acknowledged deltas accumulate with exact overflow and configured-limit checks. Tick queries are
ordered, page-bounded, and report pagination, partial eviction, and not-yet-executed ranges.

Protocol 2.1 additively exposes `simulation` and `simulationTicks` commands and the MCP tools
`runtime_simulation` and `runtime_simulation_ticks`. Protocols 1.0 through 1.13 and 2.0 retain their
closed command and response unions. Capability discovery advertises the new read-only evidence
only for exact protocol 2.1.

The application continues to own its loop, scheduling, rendering, mutation, pause behavior,
capture thread, and disposal. The runtime creates no thread, timer, executor, sleep, or hidden loop.
Only the callback's explicit return value is application testimony; the runtime does not inspect or
infer simulation behavior.

## Consequences

Agents can distinguish render activity, runtime captures, and authoritative simulation ticks, and
can map a tick to its resulting frame without screenshots. Fixed-step mismatches and partial
failure are observable. Retention stays bounded and completed immutable timeline evidence remains
queryable after close while no live callback is retained.

Applications adopting the new boundary must return the delta they actually executed. Existing
applications remain source and protocol compatible, but their legacy controlled ticks are marked
unacknowledged rather than being promoted to fixed-step evidence.
