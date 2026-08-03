# Agent tools

All tools use frozen protocol 1.0 by default, deterministic ascending frame/sequence ordering,
strict closed input schemas (`additionalProperties: false`), and a maximum requested `limit` of
1000. Query defaults are `fromFrame: 0`, `toFrame: 9223372036854775807`, and `limit: 100`.

## Tools

| Tool | Required fields | Optional fields |
| --- | --- | --- |
| `runtime_sessions` | none | none |
| `runtime_capabilities` | `sessionId` | `protocolMinor` (`0` through `3`; default `0`) |
| `runtime_frames` | `sessionId` | `fromFrame`, `toFrame`, `limit` |
| `runtime_snapshot` | `sessionId` | `frameId`, `entityId`, `entityIdPrefix`, `entityType`, `entityTypePrefix`, `limit` |
| `runtime_entity` | `sessionId`, `entityId` | `fromFrame`, `toFrame`, `limit` |
| `runtime_changes` | `sessionId` | range, `entityId`, `entityType`, `property`, `limit` |
| `runtime_events` | `sessionId` | range, `eventType`, `eventTypePrefix`, `subject`, `source`, `limit` |
| `runtime_decisions` | `sessionId` | range, `decisionType`, `actor`, `chosenCandidate`, `reasonCode`, `limit` |
| `runtime_command_status`* | `sessionId`, `commandRequestId` | none |
| `runtime_command_cancel`* | `sessionId`, `commandRequestId` | none |
| `runtime_epoch_frames` | `sessionId`, `executionEpochId` | `limit` |

\* Command tools are included in the server-start catalog only when at least one published runtime
has explicitly registered application command dispatch. They use protocol 1.2.

Every identifier is a nonblank string of at most 256 UTF-16 code units. Frame fields are
non-negative integers. Prefix matching is available only where the schema has an explicit prefix
boolean; there are no regular expressions or generic expressions.

## Protocol and capabilities

Protocol 1.0 retains the exact original capabilities result. Set `protocolMinor` to `1` or `2` only
on `runtime_capabilities` to request extension metadata. The read-only MCP tools continue to use
1.0. Command status and cancellation use protocol 1.2; epoch queries use protocol 1.3.

The 1.1 result adds `capabilityReport`, containing the runtime artifact version and stable capability
descriptors ordered by ID. Each descriptor reports availability, an unavailable reason when needed,
read-only or mutating access, Java APIs, protocol commands, MCP tools, effective limits, modes, and
dependencies. A disabled runtime reports the known inspection capabilities as unavailable with
reason `runtime-disabled`; it does not claim that capture is enabled.

Optional tools are fixed when an MCP server starts and must be backed by concrete registered
implementations. A server-wide tool that is unavailable for one selected session returns
`CAPABILITY_UNAVAILABLE`. Applications cannot publish arbitrary capability strings.

## Application command dispatch

Applications opt in by connecting the runtime to their existing capture/render-thread queue:

```java
runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .commandDispatcher(Gdx.app::postRunnable)
        .build();
```

The runtime creates no command worker, scheduler, timer, or game loop. It bounds queued commands,
maximum timeout, terminal results, expired request IDs, and diagnostic text. Concurrent submissions
reach the application dispatcher in acceptance order. Duplicate request IDs are not executed again
while retained. `runtime_command_cancel` succeeds only in `QUEUED`; after dispatch it reports the
current state without claiming cancellation. A deadline observed after execution starts reports
`TIMED_OUT` with `outcomeKnown: false` until execution completes. Status queries never wait for the
application loop and therefore remain available while that loop is paused.

## Result metadata

Protocol query pages contain:

- `items`: retained matching records;
- `hasMore`: more matches existed than the result limit;
- `requestedRangePartiallyEvicted`: requested frames predate current retention;
- `oldestRetainedFrame` and `newestRetainedFrame`.

Snapshots contain `hasMore` when an entity filter/limit omitted matching entities. Capture-level
`truncations` report dimension, observed count, retained count, and limit. These are distinct from a
query result limit.

Example event query:

```json
{
  "sessionId": "deterministic-fixture",
  "fromFrame": 20,
  "toFrame": 30,
  "eventType": "projectile.hit",
  "subject": "enemy-2",
  "limit": 10
}
```

Representative structured result:

```json
{
  "type": "events",
  "page": {
    "items": [{
      "id": {"value": 2},
      "frameId": {"value": 25},
      "type": {"value": "projectile.hit"},
      "subject": {"value": "enemy-2"},
      "source": {"value": "projectile-3"},
      "attributes": [{
        "name": "amount",
        "value": {"valueType": "integer", "value": 25}
      }],
      "truncations": []
    }],
    "hasMore": false,
    "requestedRangePartiallyEvicted": false
  }
}
```

## Errors and bounds

Errors use `SESSION_NOT_FOUND`, `FRAME_NOT_FOUND`, `ENTITY_NOT_FOUND`, `INVALID_QUERY`,
`INVALID_RANGE`, `LIMIT_EXCEEDED`, `RUNTIME_CLOSED`, `CAPTURE_NOT_AVAILABLE`,
`CAPABILITY_UNAVAILABLE`, `PROTOCOL_VERSION_UNSUPPORTED`, or `INTERNAL_ERROR`. MCP returns these in
a structured error with `isError: true`.

Raw requests are limited to 1 MiB, encoded responses to 8 MiB, JSON depth to 32, normal JSON strings
to 16384 code units, and query counts to both the protocol maximum and the runtime's configured
limit. Unknown fields, trailing JSON, unknown subtypes, and unsafe polymorphic bases are rejected.

## Bundled fixture

On Linux with Xvfb:

```bash
xvfb-run -a ./gradlew -q :runtime-fixtures:runMcpFixture
```

The fixture and MCP server share one JVM and session `deterministic-fixture`. It advances to frame
45, then retains stable state until the MCP client closes stdin.
