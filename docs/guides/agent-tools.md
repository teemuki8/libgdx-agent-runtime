# Agent tools

All tools use frozen protocol 1.0 by default, deterministic ascending frame/sequence ordering,
strict closed input schemas (`additionalProperties: false`), and a maximum requested `limit` of
1000. Query defaults are `fromFrame: 0`, `toFrame: 9223372036854775807`, and `limit: 100`.

## Tools

| Tool | Required fields | Optional fields |
| --- | --- | --- |
| `runtime_sessions` | none | none |
| `runtime_capabilities` | `sessionId` | `protocolMinor` (`0` through `10`; default `0`) |
| `runtime_frames` | `sessionId` | `fromFrame`, `toFrame`, `limit` |
| `runtime_snapshot` | `sessionId` | `frameId`, `entityId`, `entityIdPrefix`, `entityType`, `entityTypePrefix`, `limit` |
| `runtime_entity` | `sessionId`, `entityId` | `fromFrame`, `toFrame`, `limit` |
| `runtime_changes` | `sessionId` | range, `entityId`, `entityType`, `property`, `limit` |
| `runtime_events` | `sessionId` | range, `eventType`, `eventTypePrefix`, `subject`, `source`, `limit` |
| `runtime_decisions` | `sessionId` | range, `decisionType`, `actor`, `chosenCandidate`, `reasonCode`, `limit` |
| `runtime_command_status`* | `sessionId`, `commandRequestId` | none |
| `runtime_command_cancel`* | `sessionId`, `commandRequestId` | none |
| `runtime_epoch_frames` | `sessionId`, `executionEpochId` | `limit` |
| `runtime_scenarios`** | `sessionId` | none |
| `runtime_reset`** | `sessionId`, `scenarioId`, `resetRequestId`, `timeoutNanos` | none |
| `runtime_attributed_changes` | `sessionId` | range, entity/property filters, `sourceSubsystem`, `correlationId`, `limit` |
| `runtime_attributed_events` | `sessionId` | range, event/entity filters, `sourceSubsystem`, `correlationId`, `limit` |
| `runtime_attributed_decisions` | `sessionId` | range, decision filters, `sourceSubsystem`, `correlationId`, `limit` |
| `runtime_actions`*** | `sessionId` | none |
| `runtime_action`*** | `sessionId`, `action`, `actionRequestId`, `parameters`, `timeoutNanos` | `correlationId` |
| `runtime_assert` | `sessionId`, range, `executionEpochId`, `evidenceLimit`, `assertion` | assertion-specific closed fields |
| `runtime_control`**** | `sessionId`, `action` | `controlRequestId`, `timeoutNanos` for `PAUSE`/`RESUME` |
| `runtime_advance`**** | `sessionId`, `controlRequestId`, `ticks`, `deltaNanos`, `timeoutNanos` | none |
| `runtime_wait`**** | `sessionId`, `controlRequestId`, `maximumTicks`, `deltaNanos`, `evidenceLimit`, `timeoutNanos`, exactly one of `conditionId` or `assertion` | assertion-specific closed fields |
| `runtime_inputs`***** | `sessionId` | none |
| `runtime_input`***** | `sessionId`, `input`, `inputRequestId`, `parameters`, `timeoutNanos` | `targetTick` |
| `runtime_checkpoints`****** | `sessionId` | none |
| `runtime_checkpoint_create`****** | `sessionId`, `checkpointId`, `checkpointRequestId`, `timeoutNanos` | `description` |
| `runtime_checkpoint_restore`****** | `sessionId`, `checkpointId`, `checkpointRequestId`, `timeoutNanos` | none |

\* Command tools are included in the server-start catalog only when at least one published runtime
has explicitly registered application command dispatch. They use protocol 1.2.

\*\* Scenario tools are included only when at least one published runtime explicitly registers a
scenario. Reset additionally requires application command dispatch. They use protocol 1.4.

\*\*\* Action tools are included only when at least one published runtime explicitly registers an
action. Invocation additionally requires application command dispatch. They use protocol 1.6.

\*\*\*\* Control tools are included only when at least one published runtime explicitly registers a
simulation controller. Mutations additionally require application command dispatch. They use
protocol 1.8.

\*\*\*\*\* Input tools are included only when at least one published runtime explicitly registers an
input type. Injection additionally requires application command dispatch and a paused registered
simulation controller. They use protocol 1.9.

\*\*\*\*\*\* Checkpoint tools are included only when at least one published runtime explicitly
registers application-owned create, restore, and disposal callbacks. Mutation additionally requires
application command dispatch. They use protocol 1.10.

Every identifier is a nonblank string of at most 256 UTF-16 code units. Frame fields are
non-negative integers. Prefix matching is available only where the schema has an explicit prefix
boolean; there are no regular expressions or generic expressions.

## Protocol and capabilities

Protocol 1.0 retains the exact original capabilities result. Set `protocolMinor` from `1` to `9`
on `runtime_capabilities` to request extension metadata. The read-only MCP tools continue to use
1.0. Command status and cancellation use protocol 1.2; epoch queries use protocol 1.3; scenario
catalog and reset use protocol 1.4.
Attributed fact queries use protocol 1.5. Their `sourceSubsystem` is separate from the event `source`
entity ID. A `sourceLocation` in output is an unverified, bounded application-provided label;
correlation indicates association, not inferred causality.

List `runtime_actions` before invoking an action. `runtime_action.parameters` is generated from the
registered closed schema and rejects missing, unknown, or wrong-type values before handler dispatch.
Reuse the same `actionRequestId`, action, parameters, and correlation ID to poll a retained outcome;
changing any of them is rejected. A handler runs at most once while request evidence is retained.

`runtime_assert` uses protocol 1.7 and evaluates only completed retained frames in one explicit
execution epoch. Its discriminated assertion objects and nested comparison scope are closed.
Results are `PASS`, `FAIL`, or `INCONCLUSIVE`; missing frames, diagnostics, aborted decisions, or
truncation produce `INCONCLUSIVE` whenever they could change the answer. Negative, exact-count,
range-remains, and equivalence assertions require complete evidence. The evaluator never advances
simulation, sleeps, interprets expressions, or executes code.

List `runtime_inputs` before injecting an input. Applications register stable input IDs, closed
scalar parameter schemas, handlers, and an include/omit recording policy before runtime start.
`runtime_input` schedules the next controlled tick by default or a bounded explicit future tick.
Targets must be future ticks while simulation is paused. Requests execute in acceptance order on
the application-owned command/capture thread, remain at-most-once while evidence is retained, and
report requested/actual tick, epoch, submitted/resulting frame, state, and bounded diagnostics.
Only registered input facts are recorded; this API does not install global hooks or inject
operating-system input.

`runtime_checkpoints` exposes bounded descriptors only: stable checkpoint ID, source epoch/frame,
optional description, creation time, and creation request ID. `runtime_checkpoint_create` captures
an opaque application handle from the latest quiescent completed frame. `runtime_checkpoint_restore`
runs the registered callback on the application thread and, on success, creates exactly one new
`CHECKPOINT_RESTORE` epoch baseline with a fresh frame ID. Failed restores expose no baseline and
conservatively report that application state may be partially changed. Eviction and runtime close
invoke application disposal; opaque handles and payloads never cross protocol or MCP.

`runtime_control` reports availability, current pause state, registered condition IDs/descriptions,
and effective limits. `PAUSE` and `RESUME` use an idempotent `controlRequestId`.
`runtime_advance` is accepted only while paused and captures exactly one completed frame per
application-defined tick. `runtime_wait` checks one registered semantic condition or one closed
declarative assertion after each tick and stops on satisfaction, tick limit, timeout, callback
failure, or invalid state. Results retain requested/completed ticks and first/final completed frame
IDs. The application must keep servicing its command queue while paused; the runtime never owns or
sleeps the game loop.

Scenario resets are idempotently correlated by `resetRequestId`. The first response may be queued;
repeat the same reset request or read `runtime_command_status` until it is terminal. A successful
reset includes the new `executionEpochId` and completed `baselineFrameId`. Read
`runtime_scenarios` instead of inventing scenario IDs.

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

Applications may then opt into simulation control before `start()`:

```java
runtime.controls().register(SimulationControllerSpec.builder()
        .pause(() -> gamePaused = true)
        .resume(() -> gamePaused = false)
        .tick(deltaNanos -> updateOneTick(deltaNanos))
        .condition("wave-cleared", "No enemies remain", enemies::isEmpty)
        .build());
```

The tick callback updates application state inside the runtime-owned capture frame; it must not open
another frame. Pause gates normal application updates, not immutable runtime queries or the command
queue used to resume or advance.

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
