# ADR 0007: Execution epochs and discontinuity baselines

- Status: Accepted
- Date: 2026-08-03

## Context

Scenario resets and checkpoint restores replace application state discontinuously. Diffing the new
world against the previous frame would misrepresent replacement as ordinary simulation mutation,
while reusing frame zero would break stable session correlation.

## Decision

Every completed frame identifies a monotonically increasing execution epoch. Frame zero begins
epoch zero with baseline kind `INITIAL`. A successful scenario reset or checkpoint restore calls
`AgentRuntime.startEpoch` on the capture thread after application state has changed. That operation
allocates the next frame ID, begins the next epoch, captures one zero-delta baseline, and emits no
automatic structural changes against the preceding epoch.

Frame IDs, event IDs, and decision IDs remain session-global and are never reused. Retention remains
frame-bounded. Epoch-filtered queries report when the requested epoch's baseline or all of an older
epoch has been evicted. Protocol 1.3 and `runtime_epoch_frames` expose the same immutable metadata;
earlier protocol versions retain their prior tool and capability shapes.

## Consequences

Reset and restore features must claim success only after the new baseline frame completes. Failed or
indeterminate operations do not start an epoch. Comparisons and recordings can use epoch-relative
positions while retaining actual session-global frame IDs as evidence.
