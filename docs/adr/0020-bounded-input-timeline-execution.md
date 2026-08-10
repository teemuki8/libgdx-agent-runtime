# ADR 0020: Bounded input timeline execution

- Status: Accepted
- Date: 2026-08-10

## Context

Ordinary registered input injection and exact controlled simulation advancement already provide the
necessary application-owned mutation boundaries, but composing one command per transition leaves
validation, ordering, interleaving, timeout, and partial-failure evidence spread across operations.
The runtime must not take ownership of persistent gameplay input state or a game loop to close this
gap.

## Decision

One parent command submitted through the application-owned dispatcher validates, reserves, and
executes an entire bounded input timeline. Transitions are explicit local-tick facts. They execute
immediately before their requested exact tick, and transitions sharing a tick retain request-list
order.

Application handlers continue to own persistent input state, including held controls, releases,
edges, and analog behavior. The runtime does not interpolate, infer duration, or synthesize a
release. Logical child evidence reuses normal `InputInjection` values and recording hooks without
submitting a child dispatcher command for each transition.

The operation is fail-stop. Partial application mutation is neither rolled back nor retried. Hard
input, tick, canonical-evidence-size, retention, and monotonic-deadline limits fail closed with
explicit completed, failed, and not-executed testimony. Recording schema 1 and replay consume the
normal registered-input and controlled-tick evidence rather than a separate timeline event model.

## Alternatives rejected

### Schedule-only batching

A batch that only schedules transitions for later manual advancement still permits command
interleaving and leaves timeout and partial-completion evidence fragmented.

### Runtime-owned held controls

Remembering held keys, buttons, or analog state would move application gameplay state and implicit
release or interpolation policy into this third-party inspection library.

### One command per transition

Dispatching each transition separately cannot validate and reserve the whole operation before
mutation, and it exposes unrelated command scheduling between logically adjacent transitions.

## Consequences

Callers receive one bounded deterministic operation with ordered per-transition and exact-tick
evidence. Applications retain ownership of input meaning, simulation, threading, rendering, and
state. The runtime adds no loop, thread, scheduler, timer, sleep, platform input hook, rollback, or
arbitrary data boundary.
