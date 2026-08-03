# ADR 0008: Explicit resettable scenarios

## Status

Accepted

## Decision

Applications register stable scenario IDs, optional bounded descriptions, and reset callbacks
through `AgentRuntime.scenarios()`. Registration is explicit, capture-thread-owned, bounded, and
does not use reflection or object serialization.

Remote resets use the application-owned command dispatcher. A successful callback starts a new
`SCENARIO_RESET` execution epoch and captures a completed zero-delta baseline frame. The caller
supplies an idempotency key and bounded timeout; polling the same request returns retained command
status and, after success, the new epoch and frame IDs. Callback and dispatch failures use the
existing structured command diagnostics.

Protocol 1.4 adds `scenarios` and `reset`; MCP exposes them as the closed-schema
`runtime_scenarios` and `runtime_reset` tools only when a published runtime has registered scenarios.

## Consequences

Applications remain responsible for restoring a complete known state. The runtime records the new
baseline but does not infer state, reflect over objects, or serialize arbitrary graphs. Reset results
and scenario metadata have fixed configurable bounds.
