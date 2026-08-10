# ADR 0019: Bounded replay execution

## Status

Accepted — 2026-08-10

## Context

Recording schema 1 retains bounded inputs and tick correlations, but it does not retain the
selected immutable reference evidence needed to execute and compare a later run. Its
`replayGuaranteed` flag is application testimony, not executable proof. Re-querying old frames
would couple replay to shorter frame retention and would not establish that capture began from a
known origin.

## Decision

`ReplayRegistry` starts an ordinary recording plus one bounded internal sidecar. The caller names
exactly one registered deterministic scenario or retained opaque checkpoint, a comparison profile,
configuration/completeness requirements, and selected event types. Capture freezes the recreated
baseline, configured fixed step, contiguous tick evidence, and successful non-redacted registered
inputs in stable tick and acceptance order. The sidecar is retained and evicted with its recording;
schema 1 is unchanged.

Execution restores the explicit origin, compares baseline first, applies only the recorded semantic
inputs through their registered handlers, and advances exact application-owned fixed ticks. It
returns selected-evidence `EQUAL`, the first `DIVERGED` baseline/tick correlation, or
`INCONCLUSIVE`. Structural difference never claims a cause. Scenario seed/configuration is reapplied
through the reset context; checkpoint seed/configuration remains testimony and a bad restore is
detected by baseline comparison.

Semantic actions are excluded because existing recording evidence has no exact simulation-tick
execution point. OS input, reflection, object traversal, arbitrary serialization, scripts,
filesystem/network access, native state inspection, and a runtime-owned loop or worker are also
excluded. Application callbacks retain world stepping, reset/restore, input, threading, rendering,
assets, and disposal ownership.

Replay inputs, ticks, selected entities/facts, encoded bytes, retained operations, diagnostics, and
execution time have independent hard bounds. Missing, truncated, evicted, skipped, non-fixed,
unacknowledged, failed, redacted, or otherwise incomplete evidence cannot report equality.
Application failures use the existing bounded sanitized evidence without stack traces.

Protocol 2.5 and two closed same-JVM stdio MCP tools map requests and immutable results only. No
listener or cross-JVM live-inspection promise is introduced.

## Consequences

Applications can reproduce a recorded semantic-input trajectory without adopting an engine, ECS,
physics, rendering, asset, save-state, or rollback architecture. They must provide a genuinely
deterministic origin for their selected evidence and must restore native caches themselves when
those caches affect the result. Ordinary recordings remain valid but are not executable merely
because they claim replayability.
