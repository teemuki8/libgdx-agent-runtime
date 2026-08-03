# ADR 0010: Explicit semantic actions

## Status

Accepted

## Decision

Applications register semantic actions through `AgentRuntime.actions()` using a stable ID, optional
bounded description, explicit closed parameter schema, and application-owned handler. Supported
parameter types are a closed set of booleans, integers, decimals, strings, enum symbols, and entity
IDs. Registration and decoding use no reflected classes, arbitrary object mapping, methods, or
scripts.

Invocation uses the application command dispatcher and its bounded timeout, diagnostics, and
request-ID retention semantics. Validation completes before dispatch. Retained retries never execute
the handler again. Results include action and request IDs, command outcome, submitted and completed
frame IDs when available, and an optional explicit correlation ID. The application must attach that
correlation to emitted facts; the runtime does not infer effects.

Protocol 1.6 adds action catalog and invocation commands. MCP builds the `runtime_action` parameter
schema from the registered descriptors when the server starts, so nested parameter objects reject
unknown fields while accepting natural JSON scalar values.

## Consequences

The initial schema deliberately excludes nested objects, lists, arbitrary payloads, and Java types.
Applications own action semantics and frame capture. At-most-once behavior lasts only while bounded
request evidence is retained; there is no global exactly-once claim.
