# ADR 0009: Explicit fact attribution

## Status

Accepted

## Decision

Events, decisions, and explicitly caused property changes may carry `FactMetadata`: an optional
source-subsystem identifier, source-location label, and correlation ID. All values come directly
from application code. The runtime never reads stack traces, class names, bytecode, or source files.
The source-location value is bounded, immutable, and explicitly unverified testimony.

Event `source` remains an entity ID and is separate from `sourceSubsystem`. Decisions accept metadata
through an explicit `beginDecision` overload. Automatic diffs receive metadata only through an
explicit `ChangeCause`; unattributed diffs remain empty and `UNKNOWN`.

Protocol 1.5 adds attributed change, event, and decision queries with exact subsystem and correlation
filters. Separate closed MCP tools preserve the frozen base-query schemas. Correlation expresses
association only and does not make a causal claim.

## Consequences

Applications decide what source and correlation testimony is safe to expose. Metadata is copied into
immutable frames and subject to identifier and source-label bounds. Existing callers and event-source
queries remain source compatible.
