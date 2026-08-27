# Agent cookbook

This cookbook contains task-oriented, versioned recipes for coding agents integrating or operating
the runtime. Every public Java API, protocol/MCP contract, dependency, or agent-visible behavior
change must update its affected recipe in the same pull request. Examples are exercised by the
repository fixture tests.

The simulation timeline, fixed-step, assertion, determinism, replay, and input-timeline APIs
through protocol 2.6 are available in release 2.2.0. Earlier 2.0.0 artifacts do not contain the
released 2.1 or 2.2 capabilities.

## Task index

Choose the smallest recipe that answers the current question. The complete sources compile in the
non-published `runtime-examples` module as ordinary consumers of the public artifacts.

| Task | Start here | Compiled evidence |
| --- | --- | --- |
| Add observable state to a libGDX game | [Instrument and inspect state](#instrument-and-inspect-state) | [`BasicInspectionApplication.java`](../../runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/BasicInspectionApplication.java) |
| Inspect current registered state | [Instrument and inspect state](#instrument-and-inspect-state) | `runtime_entity` in the tested transcript |
| Find what changed | [Query changes, events, and decisions](#query-changes-events-and-decisions) | `runtime_changes` closed query |
| Emit and query a semantic event | [Query changes, events, and decisions](#query-changes-events-and-decisions) | `player.damaged` in `BasicInspectionApplicationTest` |
| Trace an application decision | [Decision tracing](decision-tracing.md) | application `beginDecision`/candidate/choose plus `runtime_decisions` |
| Reset a scenario | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | `runtime_reset` in the tested transcript |
| Pause and advance exact ticks | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | `runtime_control` then `runtime_simulation_advance` |
| Schedule registered input | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | `runtime_input` at epoch tick 1 |
| Create or restore a checkpoint | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | [`ControlledWorkflowExample.java`](../../runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExample.java) |
| Evaluate an assertion | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | frame and simulation assertions in the transcript |
| Record bounded execution | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | recording retrieval in `ControlledWorkflowExample` |
| Execute a replay-ready recording | [Capture and execute deterministic replay](#capture-and-execute-deterministic-replay) | Java and MCP `EQUAL` and `DIVERGED` paths in `ControlledWorkflowExample` |
| Execute a deterministic input timeline | [Execute a deterministic input timeline](#execute-a-deterministic-input-timeline) | `runtime_input_timeline` in the tested transcript and `InputTimelineResult` in `ControlledWorkflowExample` |
| Compare deterministic reruns | [Run controlled scenarios and input](#run-controlled-scenarios-and-input) | selected `EQUAL` result in both controlled examples |
| Correlate runtime and UI evidence | [Frame correlation](frame-correlation.md) | explicit `UiFrameCorrelation`, never guessed frames |
| Connect an MCP coding agent | [Host same-JVM stdio MCP](#host-same-jvm-stdio-mcp) | [`SameJvmMcpApplication.java`](../../runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/SameJvmMcpApplication.java) and [`controlled-workflow.json`](../../runtime-examples/src/main/resources/transcripts/controlled-workflow.json) |
| Interpret missing, bounded, or failed evidence | [Diagnose incomplete and failed evidence](#diagnose-incomplete-and-failed-evidence) | `AgentCookbookContractTest` and runtime fixture regressions |
| Inspect a Box2D 3 game | [Use the Box2D 3 inspection recipe](#use-the-box2d-3-inspection-recipe) | `Box2dInspectionTest` and `Box2dInspectionFixtureTest` |

Use release `3.0.0` for the Box2D 3 inspection recipe. Protocol recipes remain compatible with
their listed 2.2-era versions. Repository contributors use `3.0.0-SNAPSHOT`.

## Instrument and inspect state

Prerequisites: add the smallest published artifacts your game consumes, construct the runtime on
the libGDX render thread, and register only explicit safe properties. For released basic state
inspection:

```kotlin
implementation("io.github.teemuki8:agent-runtime-core:2.2.0")
implementation("io.github.teemuki8:agent-runtime-libgdx:2.2.0")
```

The canonical order is register, start, capture, query, close. `start()` captures baseline frame
0, and application mutation plus semantic events belong inside one application-owned frame:

```java
runtime.entities().register(EntityId.of("player"), EntityType.of("player"),
        () -> "Player", inspector -> inspector.property("health", () -> health));
runtime.start();
runtime.frame(16_666_667L, () -> {
    health = 75;
    runtime.emit(EventSpec.type("player.damaged")
            .subject(EntityId.of("player"))
            .attribute("amount", RuntimeValues.integer(25)));
});
EntitySnapshot player = runtime.entity(EntityId.of("player")).orElseThrow();
```

Representative evidence is `frameId=1`, `player.health=75`, and one explicitly emitted
`player.damaged` event. Registration, start, frame capture, and close stay on the capture thread;
completed immutable queries may run on any thread. Runtime limits bound values, property counts,
frames, and events. Truncation or eviction is evidence and must not be recast as a complete answer.
Close on the owner thread; the runtime never disposes application objects.

Do not register a mutable `Body`, actor graph, secret-bearing object, or reflective serializer.
The typed failure for an unregistered entity is absence (`Optional.empty()` or an empty query), not
permission to traverse the game. See the full hidden LWJGL3 application in
[`BasicInspectionApplication.java`](../../runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/BasicInspectionApplication.java).

## Query changes, events, and decisions

Use structural changes for observed property differences, semantic events for facts the game
explicitly emits, and decisions for an application-declared candidate choice. Query an inclusive
completed-frame range with bounded filters:

```java
QueryPage<PropertyChange> changes = runtime.changes(
        new ChangeQuery(FrameRange.of(0, 60), Optional.of(EntityId.of("player")),
                Optional.empty(), Optional.of("health"), 32));
QueryPage<RuntimeEvent> events = runtime.events(
        new EventQuery(FrameRange.of(0, 60), Optional.of("player.damaged"), false,
                Optional.of(EntityId.of("player")), Optional.empty(), 32));
```

Trace only decisions the application explicitly recorded:

```json
{"name":"runtime_decisions","arguments":{"sessionId":"game","fromFrame":0,"toFrame":60,"decisionType":"target.selected","actor":"player","limit":32}}
```

The application-side `beginDecision`, candidate, chosen/rejected, and close sequence are in
[Decision tracing](decision-tracing.md); the exact MCP query is above. The runtime never
reconstructs a decision from the final entity state.

The MCP equivalents are closed calls such as:

```json
{"name":"runtime_changes","arguments":{"sessionId":"game","fromFrame":0,"toFrame":60,"entityId":"player","property":"health","limit":32}}
```

```json
{"name":"runtime_events","arguments":{"sessionId":"game","fromFrame":0,"toFrame":60,"eventType":"player.damaged","eventTypePrefix":false,"subject":"player","limit":32}}
```

An empty retained page only says that no matching evidence was retained in that query. It does not
infer why health changed or prove an event never happened outside the complete retained range.
Inspect page completeness, eviction, and truncation fields before making a negative claim.
Decisions and events require an open frame and capture-thread ownership. Query results are frozen;
cleanup is still the runtime owner's responsibility.

Do not infer “the collision damaged the player” because a contact and health change share a frame.
Supply explicit correlation or a semantic event when the application knows that fact. Invalid
filters and unknown fields produce typed `INVALID_QUERY` transport failures.

## Run controlled scenarios and input

This workflow requires `2.2.0` or later. Register the application dispatcher,
scenario, closed input schema, optional checkpoint provider, and acknowledged fixed-step helper
before `start()`. Then use idempotent request IDs and poll the exact same request after the
application thread drains it:

```java
runtime.scenarios().reset("walk", "reset-1", timeout);
runtime.controls().control(true, "pause-1", timeout);
long tick = runtime.controls().currentTick() + 1;
runtime.inputs().inject("set-velocity", "input-1",
        RuntimeValues.object(RuntimeValues.field(
                "velocityX", RuntimeValues.decimal("2"))),
        OptionalLong.of(tick), timeout);
runtime.controls().advanceFixed("advance-1", 60, timeout);
```

Checkpoint and recording use the same dispatch/poll contract; these are the exact calls from the
compiled workflow:

```java
runtime.checkpoints().create(
        "start", "Before scheduled movement", "checkpoint-create-1", timeout);
runtime.recordings().start(new RecordingSpec(
        "walk-recording", "2.4",
        List.of(new RecordingCapabilityVersion("fixed-step-simulation", "2.2")),
        Optional.of("walk"), Optional.of("start"), OptionalLong.of(7),
        RuntimeValues.object(), false), "recording-start-1", timeout);
// inject and advance exact ticks
runtime.recordings().stop("walk-recording", "recording-stop-1", timeout);
RecordingChunk recording = runtime.recordings().get("walk-recording", 0, 64);
runtime.checkpoints().restore("start", "checkpoint-restore-1", timeout);
```

This ordinary recording is not executable replay evidence, even if its application testimony sets
`replayGuaranteed=true`. Use the replay-ready start path below to capture the bounded comparison
sidecar.

After application dispatch, create/stop/restore report command `SUCCEEDED`; the chunk contains a
bounded `RecordingInputEntry` and the exact retained `RecordingTickEntry` values. Restore produces
a new epoch baseline and the example verifies position `(0,0)`. A recording chunk explicitly
reports truncation/eviction rather than fabricating missing entries.

The scheduled input executes immediately before its selected tick. An acknowledged tick records
the configured, supplied, and application-reported executed delta plus its resulting runtime
frame. Assertions consume completed immutable evidence and return `PASS`, `FAIL`, or
`INCONCLUSIVE`. Recordings and deterministic comparison reuse the same scenario/input/tick path;
`EQUAL` applies only to the selected evidence.

Submitted commands may first return `QUEUED` or `EXECUTING`. The application must drain its own
dispatcher and retry with the identical request ID and fields. Reusing an ID with changed fields is
rejected; retrying unknown partial mutation is unsafe until the application resets. Tick counts,
future scheduling, retained operations, recordings, evidence, and execution time are bounded.
Restore/reset callbacks must restore application state and clear or restore the accumulator.

Do not call a mutation from an MCP worker or invent a loop with `Thread.sleep`. The complete
application-owned Java sequence—including checkpoint restore, recording, PASS/FAIL assertions,
and selected reruns—is
[`ControlledWorkflowExample.java`](../../runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/ControlledWorkflowExample.java).

## Capture and execute deterministic replay

Protocol 2.5 adds bounded execution for recordings explicitly started through
`ReplayRegistry`. Pause first, choose exactly one registered scenario or retained checkpoint
origin, select observable evidence, then inject registered inputs and advance contiguous fixed
ticks. The compiled workflow uses a scenario origin:

```java
RecordingSpec recording = new RecordingSpec(
        "walk-recording", "2.5", capabilityVersions,
        Optional.of("walk"), Optional.empty(), OptionalLong.of(7),
        RuntimeValues.object(), true);
ReplayCaptureSpec capture = new ReplayCaptureSpec(
        recording, profile, configurationRequirements, evidenceRequirements, eventTypes);
runtime.replays().start(capture, "replay-capture-1", timeout);
// drain on the application thread; inject registered input; advance exact fixed ticks
runtime.recordings().stop("walk-recording", "recording-stop-1", timeout);
runtime.replays().execute("walk-recording", "replay-execute-1", timeout);
ReplayResult result = runtime.replays()
        .execute("walk-recording", "replay-execute-1", timeout)
        .result().orElseThrow();
```

`EQUAL` means only the selected baseline and every retained tick observable matched. `DIVERGED`
stops at the first complete difference: `BASELINE` has no tick ID, while `SIMULATION_TICK` includes
the reference/replay epoch, tick, and frame correlations. The difference is observable evidence,
not a claim that the recorded input caused it. `INCONCLUSIVE` covers ordinary recordings, missing
or truncated evidence, skipped/non-fixed/unacknowledged ticks, actions, failed or redacted inputs,
eviction, timeout, and bounded application failure. Application callback evidence never contains a
serialized stack trace.

The compiled DIVERGED path in `ControlledWorkflowExample` uses a second application-owned
scenario whose registered input behavior changes between capture and replay. Its tested result is
`SIMULATION_TICK` at epoch tick 1 with zero prior completed replay ticks. This deliberately reports
the first structural mismatch without claiming why the application behavior changed.

The checkpoint form changes only the origin fields: `scenarioId` is empty and `checkpointId` names
one retained opaque application checkpoint. Its seed/configuration remain testimony; baseline
comparison detects a bad restore. The application must restore all state that affects its selected
observables, including native physics caches where relevant.

The closed MCP calls used by the tested transcript are:

```json
{"name":"runtime_replay_recording_start","arguments":{"sessionId":"game","recordingId":"walk-recording","replayRequestId":"capture-1","originKind":"scenario","originId":"walk","randomSeed":7,"configuration":[],"profile":{"comparisonScope":{"entityIds":["player"],"properties":["position","velocity"],"excludedProperties":[],"includeEvents":false,"includeDecisions":false},"includeUiCorrelations":false},"configurationRequirements":[],"evidenceRequirements":[],"eventTypes":[],"timeoutNanos":5000000000}}
```

```json
{"name":"runtime_replay","arguments":{"sessionId":"game","recordingId":"walk-recording","replayRequestId":"execute-1","timeoutNanos":5000000000}}
```

Poll either call with the identical fields while its command is `QUEUED` or `EXECUTING`. Protocol
2.4 rejects both commands as requiring 2.5. Sidecars, scripts, tick evidence, operations, encoded
bytes, and deadlines are independently bounded; recording or operation eviction is explicit.

## Execute a deterministic input timeline

Protocol 2.6 adds one bounded, application-dispatched operation that validates and executes an
ordered sequence of explicit registered input transitions through exact fixed simulation ticks.
Pause first with an acknowledged controller and a configured fixed step, then submit the whole
timeline as one parent command. The compiled workflow uses a 60-tick scenario timeline:

```java
InputTimelineSpec timeline = new InputTimelineSpec(60, List.of(
        new InputTimelineTransition("workflow-velocity", 1, "set-velocity",
                velocityParameters(2))));
runtime.inputs().executeTimeline(timeline, "workflow-input-timeline", timeout);
// drain on the application thread, then poll with the identical request ID, spec, and timeout
InputTimelineResult result = runtime.inputs()
        .executeTimeline(timeline, "workflow-input-timeline", timeout)
        .result().orElseThrow();
```

A timeline-local tick is relative to the timeline: local tick 1 is the next session controlled
tick (`startingControlledTick + 1`), and every transition executes immediately before its local
tick. The session controlled tick counter and epoch-relative simulation ticks remain authoritative
in the resulting `InputInjection` and `SimulationTick` evidence, so callers can map local ticks,
session ticks, and epoch ticks without guessing. Ticks without transitions still advance by the
registered fixed step (idle ticks). Transitions sharing a tick execute in request-list order, so
explicit boolean start/stop pairs and decimal analog values behave deterministically:

```java
new InputTimelineSpec(2, List.of(
        new InputTimelineTransition("move-left-on", 1, "move-left",
                RuntimeValues.object(RuntimeValues.field(
                        "active", RuntimeValues.bool(true)))),
        new InputTimelineTransition("steer", 1, "steer",
                RuntimeValues.object(RuntimeValues.field(
                        "amount", RuntimeValues.decimal("0.5")))),
        new InputTimelineTransition("move-left-off", 2, "move-left",
                RuntimeValues.object(RuntimeValues.field(
                        "active", RuntimeValues.bool(false))))));
```

Held inputs are never inferred: the application must send both the `true` start and the `false`
stop. Parameters stay closed scalar values (boolean, integer, decimal, string, enum, entity ID)
validated against the registered descriptor before any handler runs.

The closed MCP call used by the tested transcript is:

```json
{"name":"runtime_input_timeline","arguments":{"sessionId":"controlled-workflow-example","timelineRequestId":"transcript-input-timeline","totalTicks":2,"transitions":[{"transitionId":"transcript-velocity","timelineTick":1,"inputId":"set-velocity","parameters":{"velocityX":2}}],"timeoutNanos":5000000000}}
```

Poll with the identical fields while the parent command is `QUEUED` or `EXECUTING`; reusing the
parent request ID with changed ticks, order, transition identity, input, parameters, or timeout is
rejected. The terminal `InputTimelineResult` reports the stop reason plus requested/completed
ticks and requested/executed/failed/not-executed transitions (`NOT_EXECUTED` transitions carry a
bounded deterministic reason and no fabricated injection). `COMPLETED` requires every tick and
transition inside the deadline; `TIMED_OUT`, `LIFECYCLE_CHANGED`, `INPUT_FAILED`, `TICK_FAILED`,
`EVIDENCE_LIMIT`, and `CLEANUP_FAILED` retain authoritative completed work without claiming
success. Execution is fail-stop: after the first failed transition or tick, later same-tick and
later transitions are `NOT_EXECUTED`, and partial application mutation is never rolled back or
retried. After a timeout or partial failure, recover by resetting or restoring a known
scenario/checkpoint before deciding to retry under a new request ID.

Recording schema 1 and replay consume the timeline's normal registered-input and controlled-tick
evidence: successful timelines record normal `RecordingInputEntry` values and controlled-tick
entries and replay `EQUAL`; failed, redacted, timed-out, lifecycle-invalidated, or otherwise
incomplete timeline evidence makes replay capture inconclusive. The transcript proves the same
trajectory through the later entity, event, assertion, and replay steps.

## Host same-JVM stdio MCP

MCP is a local development transport, not remote attachment. Publish the started runtime and open
the server inside the same libGDX JVM:

```java
publication = registry.publish(runtime);
server = RuntimeMcpServer.open(
        new RuntimeProtocolService(registry), System.in, System.out);
```

Send MCP `initialize`, `notifications/initialized`, then closed `tools/call` requests. The tested
[`controlled-workflow.json`](../../runtime-examples/src/main/resources/transcripts/controlled-workflow.json)
transcript covers sessions, capabilities, scenarios, reset, pause, replay-ready capture, scheduled
input, configured-step advance, entity/event inspection, frame and simulation assertions, equal and
first-divergence replay, and simulation determinism. Representative terminal results include
command `SUCCEEDED`, assertion `PASS`, selected comparison `EQUAL` with a null divergence, and
`DIVERGED` at `SIMULATION_TICK` epoch tick 1.

`System.out` is exclusively newline-framed JSON-RPC. Put human logs on stderr or in a bounded file.
The game owns dispatch via `Gdx.app.postRunnable`; the server creates no game loop. Inputs, nesting,
strings, result lists, and frames use the runtime's hard protocol/MCP bounds, and unknown fields are
rejected before dispatch. Close in order: server, publication, runtime. EOF is a clean launcher
shutdown signal.

Do not run a catalog-only MCP JVM beside the game and expect it to inspect process memory. Do not
open both runtime and UI-harness stdio servers on the same streams. See the runnable hidden launcher
in [`SameJvmMcpApplication.java`](../../runtime-examples/src/main/java/io/github/teemuki8/libgdx/agent/runtime/examples/SameJvmMcpApplication.java).

Prepare the tested application distribution once (and after dependency/source changes):

```bash
./gradlew :runtime-examples:installDist
```

The repository wrapper invokes the installed script directly. It also preserves the original
stderr on file descriptor 3 because Debian/Ubuntu `xvfb-run` otherwise merges child stderr into
stdout. The MCP command therefore emits only JSON-RPC on stdout while Java/LWJGL diagnostics remain
on stderr. From the repository, use this client configuration:

```json
{
  "mcpServers": {
    "libgdx-runtime-example": {
      "command": "./runtime-examples/run-mcp-example-xvfb.sh",
      "args": []
    }
  }
}
```

## Diagnose incomplete and failed evidence

Interpret evidence conservatively:

| Observation | Meaning | Agent action |
| --- | --- | --- |
| entity/query absent with complete retained range | no matching registered evidence in that range | verify ID and registration; do not infer application semantics |
| range partially evicted or paginated | requested evidence is incomplete | narrow/repeat the query or return `INCONCLUSIVE` |
| truncation/limit diagnostic | only a bounded prefix was retained | raise an application-configured limit or reduce explicit scope |
| command `QUEUED`/`EXECUTING` | application thread has not completed it | drain dispatch and poll the identical request |
| command `FAILED` with mutation unknown | callback may have partially changed state | reset/restore before retrying |
| assertion `FAIL` | complete evidence contradicts the expected fact | inspect typed expected/observed/evidence fields |
| assertion `INCONCLUSIVE` | PASS/negative proof is unsafe | inspect eviction, truncation, missing correlation, and completeness |
| determinism `DIVERGED` | selected evidence first differs at the reported tick | inspect entity/property or event difference for both runs |
| determinism `INCONCLUSIVE` | setup or evidence could not support equality | fix the named timing/configuration/completeness fault |

For negative claims, absence is not proof when any relevant evidence is evicted, truncated,
unmapped, failed, unacknowledged, or missing frame correlation. A screenshot can supplement these
facts but never makes incomplete structured evidence complete. Diagnostics are closed typed values;
the runtime does not serialize a stack trace or infer causality.

Incorrect example: treating an empty contact-event page as “contact never occurred” after contact
history eviction. The correct result is `INCONCLUSIVE`, followed by a reset and a smaller exact-tick
range or larger application-selected retention limit.

### Minimal structured failure reproductions

These are failure boundaries, not strings to pattern-match. Inspect the typed result or error code.

| Deliberate reproduction | Expected structured outcome | Recovery |
| --- | --- | --- |
| send a protocol 2.5 request to this 2.4 development server | `UNSUPPORTED_VERSION`; no command dispatch | negotiate a listed version and rebuild the request |
| omit `commandDispatcher`, scenario, input, or fixed-step registration | the dependent capability/tool is absent; a forced MCP call is `INVALID_QUERY` | register on the application thread before publishing |
| submit reset/pause/input and do not drain the application dispatcher | command state remains `QUEUED` or `EXECUTING` | drain application work and poll the identical request |
| reuse one request ID for a different command or fields | Java rejects conflicting correlation with `IllegalArgumentException`; MCP returns a bounded invalid-query result | allocate a new ID or restore the original fields |
| call fixed-step update from a non-capture thread | `AgentRuntimeException` with `WRONG_THREAD` | post work to the application/capture thread |
| update/register/close while a frame is open | `AgentRuntimeException` with `INVALID_LIFECYCLE` | complete or abort the frame before lifecycle mutation |
| time out or cancel after a callback starts | command reports timeout/failure and may carry unknown mutation outcome | reset or restore before retrying |
| query a missing/evicted frame, entity history, or simulation tick | `FRAME_NOT_FOUND`, `ENTITY_HISTORY_NOT_RETAINED`, or partial-eviction/`NOT_YET_EXECUTED` evidence | narrow to a retained exact range or reproduce from reset |
| exceed value/contact/shape/history limits | explicit truncation/eviction diagnostics and completeness false | reduce registered scope or change an application-owned bound |
| evaluate a negative/whole-range assertion over incomplete evidence | `INCONCLUSIVE`, never misleading `PASS` | restore complete evidence and rerun |
| compare with missing correlation, timing mismatch, or incomplete evidence | determinism `INCONCLUSIVE`, never `EQUAL` | fix the named setup/evidence diagnostic |
| throw from an application callback | bounded `ApplicationFailureEvidence` exposes category, exception class, correlation ID, and optional sanitized detail; no raw message/stack trace | inspect local logs by correlation, then reset if mutation is unknown |
| write game logs to stdout while MCP is active | JSON-RPC framing is contaminated and the client receives a parse/transport failure | reserve stdout for MCP and move logs to stderr/file |
| start MCP in a separate JVM from the live game | only that process's registry/catalog is visible; the game session is absent | embed the server in the game development launcher |
| report a different executed delta, omit a colliding fixture, use a suspicious unit expectation, or overrun catch-up | `DELTA_MISMATCH`, incomplete contact evidence, assertion `FAIL`, or clamp/drop diagnostics | correct the application testimony/registration/scale or bounded update policy |

### Exact failure calls

Unsupported version and absent-capability calls fail before application dispatch:

```java
RuntimeResponse.Failure unsupported = (RuntimeResponse.Failure) service.execute(
        new RuntimeRequest(new ProtocolVersion(2, 5), "bad-version", null,
                new RuntimeCommand.Sessions()));
assert unsupported.error().code() == ProtocolErrorCode.UNSUPPORTED_VERSION;
```

```json
{"name":"runtime_reset","arguments":{"sessionId":"basic-inspection-example","scenarioId":"walk","resetRequestId":"missing-capability","timeoutNanos":1000000000}}
```

The second call is `INVALID_QUERY` because that basic runtime registered neither a scenario nor an
application dispatcher, so the server catalog does not advertise `runtime_reset`.

Queued polling and conflicting correlation are reproduced without a clock or worker:

```java
ScenarioReset queued = runtime.scenarios().reset("walk", "same-id", timeout);
assert queued.command().status().orElseThrow().state() == CommandState.QUEUED;
ScenarioReset same = runtime.scenarios().reset("walk", "same-id", timeout); // same operation
assertThrows(IllegalArgumentException.class,
        () -> runtime.controls().control(true, "same-id", timeout));
applicationQueue.removeFirst().run();
ScenarioReset completed = runtime.scenarios().reset("walk", "same-id", timeout);
assert completed.command().status().orElseThrow().state() == CommandState.SUCCEEDED;
```

Wrong-thread and open-frame lifecycle errors retain their exact local categories:

```java
Thread.ofPlatform().start(() -> {
    AgentRuntimeException failure = assertThrows(
            AgentRuntimeException.class, () -> simulation.updateNanos(0));
    assert failure.code() == RuntimeErrorCode.WRONG_THREAD;
}).join();
runtime.frame(1, () -> {
    AgentRuntimeException failure = assertThrows(
            AgentRuntimeException.class, () -> simulation.updateNanos(0));
    assert failure.code() == RuntimeErrorCode.INVALID_LIFECYCLE;
});
```

For a queued command, cancel by its command request ID before dispatch and inspect
`CommandCancellation`; after dispatch begins, cancellation cannot prove rollback. If status becomes
`TIMED_OUT` or `FAILED` with `mutationOutcome=UNKNOWN`, reset or restore instead of resubmitting.

```java
CommandCancellation cancelled = runtime.commands().orElseThrow().cancel("queued-request");
SimulationTickPage ticks = runtime.simulation().ticks(
        new SimulationTickQuery(epoch, 1, 60, 60));
if (!ticks.complete()) {
    assert assertion.status() == AssertionStatus.INCONCLUSIVE;
    assert determinism.status() == DeterminismStatus.INCONCLUSIVE;
}
```

Use deliberately small application limits to reproduce truncation; inspect the typed loss rather
than an empty list:

```java
Box2dContactLimits limits = new Box2dContactLimits(1, 1, 1, 1, 1, 8, 16, 16);
// A tick with two callbacks retains one record, emits RECORD_LIMIT_REACHED, and complete=false.
```

Sanitize application exceptions at construction. The callback's raw message and stack trace never
enter protocol evidence:

```java
AgentRuntime runtime = AgentRuntime.builder()
        .applicationFailureSanitizer(
                (context, failure) -> Optional.of("reset callback failed"))
        .build();
// ApplicationFailureEvidence = category + exceptionClass + correlationId + bounded detail.
```

For stdio contamination, the minimal incorrect call is `System.out.println("game started")` after
opening `RuntimeMcpServer`; the next client read is not a JSON-RPC object. Put that message on
`System.err`. For a separate-JVM failure, start a new `RuntimeRegistry` process and call
`runtime_sessions`: the live game session is absent because registries are process-local.

## Use the Box2D 3 inspection recipe

Use `agent-runtime-box2d:3.0.0` with the official
`com.badlogicgames.gdx:gdx-box2d:3.1.1-0` binding. The application owns every native ID, steps the
world, and destroys joints before shapes, bodies, and the world. The runtime only copies bounded
facts from explicitly registered live IDs.

The complete construction and close recipe is in
[`box2d-inspection.md`](box2d-inspection.md). The native regression is
`Box2dInspectionTest`; the unpublished fixture repeats the capsule path across the fixture module.

## Fixed-step simulation ticks

Use this when game correctness depends on Box2D or another authoritative fixed-step simulation.
Rendering stays outside the tick callback. Use the canonical helper instead of copying accumulator
logic into each game.

```java
private static final long FIXED_STEP_NANOS = 16_666_667L;

runtime = LibGdxAgentRuntime.builder()
        .captureThread(Thread.currentThread())
        .commandDispatcher(Gdx.app::postRunnable)
        .build();
FixedStepSimulationConfiguration configuration =
        FixedStepSimulationConfiguration.developmentDefaults(FIXED_STEP_NANOS);
LibGdxFixedStepSimulation simulation = LibGdxFixedStepSimulation.acknowledged(
        runtime, configuration, tick -> {
            processGameInput();
            Box2d.b2World_Step(world, tick.fixedStepSeconds(), 4);
            gameLogicAfterPhysics();
            return tick.fixedStepNanos(); // the delta actually executed
        });
registerInspectableState(runtime);
runtime.start();

// render(): the application still owns when update and render occur.
FixedStepUpdateReport report = simulation.update(Gdx.graphics.getDeltaTime());
renderGame(simulation.interpolationAlpha());
```

The immutable configuration bounds render delta, accumulated time, catch-up ticks, and retained
update reports. The helper uses integer nanoseconds for accumulation and calculates one canonical
float step for APIs such as Box2D. The callback return remains explicit application testimony; the
generic helper does not infer that application code used the supplied float.

Inspect `report.diagnostics()` on every non-empty diagnostic result. For example:

```text
suppliedRenderDeltaNanos: 500000000
acceptedRenderDeltaNanos: 250000005
clampedRenderTimeNanos: 249999995
ticksAttempted: 8
ticksCompleted: 8
catchUpDroppedTimeNanos: 116666669
droppedTicks: 7
diagnostics: [RENDER_DELTA_CLAMPED, CATCH_UP_TICKS_DROPPED]
```

Dropped time is intentional loss evidence, never successful simulation time. Interpolation alpha
comes only from the retained sub-step remainder and reading it never mutates authoritative state.

Success evidence has distinct identities and timing:

```text
simulationTickId: 1
executionEpochId: 0
epochTick: 1
configuredFixedStepNanos: 16666667
runtimeSuppliedDeltaNanos: 16666667
executedDeltaNanos: 16666667
epochSimulationTimeNanos: 16666667
resultingFrameId: 1
source: RUNNING
outcome: COMPLETED
```

Do not use `FrameSnapshot.deltaNanos` as proof that simulation executed that delta. A runtime frame
is observation evidence. The callback return is explicit application testimony. If it reports
`33333334` while the fixed step is `16666667`, the tick outcome is `DELTA_MISMATCH` and the
diagnostic names the supplied/configured mismatch.

For exact controlled stepping, pause through the existing application dispatcher and advance with
the registered fixed step. There is no caller-selected delta:

```java
runtime.controls().control(true, "pause", Duration.ofSeconds(1));
runtime.controls().advanceFixed("two-ticks", 2, Duration.ofSeconds(1));
```

The equivalent MCP tool is `runtime_simulation_advance`. Scheduled registered input runs before
the controlled callback. Controlled ticks bypass and preserve the render accumulator remainder.
The legacy unacknowledged registration form remains available only when configuration explicitly
allows it and produces `UNACKNOWLEDGED` timeline evidence.

`runtime.controls().pauseStateKnown()` distinguishes the last successfully applied pause value
from an application callback whose mutation may be partial. A failed pause/resume callback makes
this value false. Determinism then returns sanitized `INCONCLUSIVE` restore/pause evidence and will
not execute another comparison until an explicit application-dispatched pause or resume succeeds.

From a scenario reset or checkpoint restore callback, explicitly clear or restore accumulator
state before the new epoch baseline:

```java
resetGameState();
runtime.fixedStepSimulation().clearAccumulator();
```

### Inspect through Java

```java
SimulationState state = runtime.simulation().state();
SimulationTickPage page = runtime.simulation().ticks(new SimulationTickQuery(
        runtime.currentEpoch(), 1, 60, 60));
if (!page.complete()) {
    // Inspect rangeStatus(): PAGINATED, PARTIALLY_EVICTED, or NOT_YET_EXECUTED.
}

FixedStepSimulationState accumulator = runtime.fixedStepSimulation().state();
FixedStepUpdatePage updates = runtime.fixedStepSimulation().updates(
        new FixedStepUpdateQuery(1, 120, 120));
```

Tick queries are safe from any thread after ticks complete. Mutation, registration, and tick
execution remain capture-thread owned. A scenario reset or checkpoint restore creates a new epoch;
its baseline is not a tick, its first tick is epoch tick 1, and session tick IDs are not reused.

### Inspect through MCP

Request current state:

```json
{"name":"runtime_simulation","arguments":{"sessionId":"game"}}
```

Request an inclusive bounded range:

```json
{"name":"runtime_simulation_ticks","arguments":{"sessionId":"game","executionEpochId":0,"fromEpochTick":1,"toEpochTick":60,"limit":60}}
```

Both tools have closed inputs and use exact protocol 2.1. Protocol 2.0 rejects the commands. A
callback failure may leave application mutation unknown and may still have a completed resulting
frame; inspect both `outcome` and `mutationOutcome` before deciding whether retry/reset is safe.

Inspect fixed-step state and loss reports through exact protocol 2.2:

```json
{"name":"runtime_fixed_step","arguments":{"sessionId":"game"}}
```

```json
{"name":"runtime_fixed_step_updates","arguments":{"sessionId":"game","fromSequence":1,"toSequence":120,"limit":120}}
```

Advance paused simulation without a delta field:

```json
{"name":"runtime_simulation_advance","arguments":{"sessionId":"game","controlRequestId":"step-60","ticks":60,"timeoutNanos":1000000000}}
```

Protocol 2.1 rejects these 2.2 commands. Reuse the same advance request ID and fields to poll its
at-most-once operation; changing them is rejected. A failed attempted tick is consumed, remaining
whole accumulator time is reported as dropped, and only the sub-step remainder is retained so an
unknown partial mutation is not silently replayed.

Failure reports preserve the retained simulation-tick and runtime-frame correlation whenever that
evidence exists. Inspect the closed diagnostics rather than treating every thrown update alike:
`APPLICATION_CALLBACK_FAILED`, `RUNTIME_CAPTURE_FAILED`, `EXECUTED_DELTA_INVALID`, and
`SIMULATION_TIME_LIMIT_EXCEEDED` distinguish the failure boundary; `TICK_FAILED` is the common
failure marker. Invalid executed-delta and epoch-time-limit outcomes may have completed application
mutation and capture even though the update rethrows, so inspect the correlated timeline outcome
before retrying or resetting.

Close the runtime on its capture thread. Completed immutable tick pages remain queryable after
close; no new tick is accepted.

## Inspect registered Box2D 3 state

Initialize the official binding and create native objects with Box2D 3 definitions:

```java
Box2d.initialize();
b2WorldDef worldDef = Box2d.b2DefaultWorldDef();
worldDef.workerCount(0);
b2WorldId world = Box2d.b2CreateWorld(worldDef.asPointer());

b2BodyDef bodyDef = Box2d.b2DefaultBodyDef();
bodyDef.type(b2BodyType.b2_dynamicBody);
b2BodyId body = Box2d.b2CreateBody(world, bodyDef.asPointer());

b2ShapeDef shapeDef = Box2d.b2DefaultShapeDef();
shapeDef.density(1.0f);
b2Capsule capsule = new b2Capsule();
capsule.center1().y(-0.5f);
capsule.center2().y(0.5f);
capsule.radius(0.25f);
b2ShapeId shape = Box2d.b2CreateCapsuleShape(
        body, shapeDef.asPointer(), capsule.asPointer());

Box2dInspection physics = new Box2dInspection(
        runtime, Box2dAdapterLimits.developmentDefaults());
Box2dRegistration<b2WorldId> mainWorld = physics.registerWorld(
        "main", world, new Box2dWorldSpec(4, new Box2dUnitTransform(100)));
Box2dRegistration<b2BodyId> player = physics.registerBody("player", "main", body);
Box2dRegistration<b2ShapeId> playerShape = physics.registerShape(
        "player-shape", "player", shape, Box2dShapeSpec.defaults());
```

Create a joint only after both endpoint bodies are registered, then register its `b2JointId`.
Runtime entity IDs remain `box2d.world.*`, `box2d.body.*`, `box2d.fixture.*`, and
`box2d.joint.*`. Evidence contains copied vectors, numbers, enums, strings, lists, and objects only;
native handle fields, structs, and pointers are never serialized.

Close registrations before native destruction. Descendants close first:

```java
jointRegistration.close();
playerShape.close();
player.close();
mainWorld.close();
physics.close();

Box2d.b2DestroyJoint(joint);
Box2d.b2DestroyShape(shape, true);
Box2d.b2DestroyBody(body);
Box2d.b2DestroyWorld(world);
```

Registration, rebind, and close require the owner thread and no open runtime frame. Invalid or stale
IDs, duplicate native keys, wrong-world relationships, missing joint endpoints, registration
bounds, closed adapters, and parent-before-child close all fail rather than producing partial
evidence.

## Capture bounded Box2D 3 contacts

Register contact capture after the world, bodies, and shapes. The adapter owns one bounded native
contact-data scratch buffer but never owns or steps the world:

```java
Box2dContacts contacts = physics.registerContacts(
        "main",
        Box2dContactLimits.developmentDefaults(),
        Box2dContactPolicy.developmentDefaults());
```

Wrap exactly one application-owned step inside each acknowledged simulation tick:

```java
contacts.captureStep(() ->
        Box2d.b2World_Step(world, tick.fixedStepSeconds(), worldSpec.subStepCount()));
```

After the step, the adapter immediately copies `b2ContactEvents`. Begin and end events retain only
stable registered endpoints. Hit events retain copied point and normal values and correlate bounded
`b2Shape_GetContactData` results to the same stable pair. The `normal` impulse is the maximum
positive `b2ManifoldPoint.totalNormalImpulse`, accumulated across substeps and restitution; the
final-substep `normalImpulse` is intentionally not used.

Records and active contacts keep the existing `box2d.contacts.<worldId>` entity and
`box2d.contact.begin`, `box2d.contact.end`, and `box2d.contact.postSolve` event schemas. Native
event arrays, IDs, manifold pointers, and contact buffers never cross the capture call. Bounds fail
complete evidence with diagnostics rather than silently truncating.

World rebind and scenario reset clear active evidence before the next baseline. Close
`Box2dContacts` or its parent inspection before native destruction; close releases its manually
owned contact-data buffer.

## Assert physics over exact simulation ticks

Use this recipe after the application has captured the authoritative ticks. Simulation assertions
read completed immutable timeline/frame evidence; they never call a Box2D getter, advance the game,
render, sleep, or execute agent-supplied code.

### Java workflow

Create an inclusive epoch-relative tick scope and evaluate a data-only specification:

```java
SimulationAssertionScope ticks = new SimulationAssertionScope(
        runtime.currentEpoch(), 1, 60, 8);

SimulationAssertionResult resting = runtime.assertions().evaluateSimulation(
        Box2dAssertions.bodyStopped("ball", 0.01, 0.01), ticks);

Box2dAssertions.ContactEndpoint ball =
        new Box2dAssertions.ContactEndpoint("ball", "ball-shape", 0);
Box2dAssertions.ContactEndpoint ground =
        new Box2dAssertions.ContactEndpoint("ground", "ground-shape", 0);
SimulationAssertionResult landed = runtime.assertions().evaluateSimulation(
        Box2dAssertions.contactOccurred("main", ball, ground), ticks);
```

The complete Box2D factory set is:

| Factory | Evidence predicate |
| --- | --- |
| `bodyExists` | final `box2d.body.<id>` exists |
| `bodyPositionApproximately` | final `position`, component or Euclidean tolerance |
| `bodyVelocityApproximately` | final `linearVelocity`, component or Euclidean tolerance |
| `bodySleeping` / `bodyAwake` | final exact `awake` boolean |
| `bodyStopped` | final linear magnitude and angular absolute tolerance |
| `bodyInsideArea` / `bodyOutsideArea` | final position and a closed inclusive area |
| `bodyRemainedWithinBounds` | position is inside the closed area at every tick |
| `bodyDistanceApproximately` | final distance between two body positions |
| `bodyAngleApproximately` | final angle with wrapped `2*pi`-radian distance |
| `contactOccurred` / `contactDidNotOccur` | exact canonical begin-event count |
| `contactRemainedActive` | exact contact appears in `activeContacts` at every tick |
| `bodyNeverExceededSpeed` | inclusive linear-speed maximum at every tick |

Factories accept stable registration IDs, not runtime entity IDs or native objects. A contact
endpoint is `(bodyId, fixtureId, childIndex)`; the factory canonicalizes the pair exactly like
contact capture. Every contact factory adds the evidence requirement
`box2d.contacts.<worldId>.complete == true`. `contactRemainedActive` reads each tick's active set; it
does not infer continuity from adjacent begin/end events.

### Final and whole-range semantics

`FINAL` predicates inspect only `toEpochTick`. `EVERY_TICK` predicates and event counts inspect the
closed range in ascending tick order. Areas and maximum/tolerance boundaries are inclusive.
Component vector tolerance checks each coordinate. Euclidean vector, magnitude, and distance checks
use deterministic squared `BigDecimal` arithmetic. Wrapped angles use the explicit positive period
and an inclusive absolute tolerance no greater than half that period.

A complete mismatch is `FAIL` and identifies the first complete violation. A negative or
every-tick assertion becomes `PASS` only after every relevant tick is proven complete. Any relevant
missing/evicted tick, failed or unknown tick outcome, wrong epoch, absent frame correlation, capture
diagnostic/truncation, or false/missing explicit requirement yields `INCONCLUSIVE` when it could
change the answer. A later unrelated incomplete tick does not hide an earlier decisive complete
failure.

One request evaluates at most 1,000 ticks, returns at most 100 evidence items, accepts at most eight
evidence requirements, and permits at most eight non-composite `allOf` terms. Assertion value trees
are separately bounded to 16 levels, 1,024 nodes, 256 values per collection, and 4,096 UTF-16 code
units per string. Event/object selectors are stricter: four levels, 32 nodes, 16 fields per object,
1,024-character strings, and no selector lists or property paths. Evaluation may inspect a valid
captured value that is larger than the assertion-result tree bound; the status still reflects that
immutable value, while optional `observed` projections that cannot fit are omitted instead of
throwing or returning a misleading partial tree.

### Closed assertion tags

The generic `SimulationAssertion` tags and exact record fields are:

| Tag | Fields after `assertionType` |
| --- | --- |
| `entityExists` | `entityId` |
| `propertyEquals` | `entityId`, `property`, `expected` |
| `scalarApproximatelyEquals` | `entityId`, `property`, `expected`, `absoluteTolerance` |
| `vectorApproximatelyEquals` | `entityId`, `property`, `expected`, `absoluteTolerance`, `toleranceMode` |
| `vectorInArea` | `entityId`, `property`, `area`, `relation`, `extent` |
| `vectorMagnitudeAtMost` | `entityId`, `property`, `maximum`, `extent` |
| `vectorDistanceApproximatelyEquals` | `leftEntityId`, `leftProperty`, `rightEntityId`, `rightProperty`, `expectedDistance`, `absoluteTolerance` |
| `wrappedAngleApproximatelyEquals` | `entityId`, `property`, `expected`, `period`, `absoluteTolerance` |
| `eventCount` | protocol: `selector`, `expectation`, `exactCount`; MCP: flattened selector fields below |
| `objectListContains` | `entityId`, `property`, `selector`, `extent` |
| `allOf` | `terms` (two through eight non-composite assertions) |

Closed enum values are `FINAL`/`EVERY_TICK`, `COMPONENT`/`EUCLIDEAN`, `INSIDE`/`OUTSIDE`, and
`AT_LEAST_ONE`/`NONE`/`EXACT`. For `EXACT`, `exactCount` is 1 through 1,000,000; the other event
expectations require `exactCount: 0`. An event selector contains exact `eventType`, optional exact
`subject` and `source`, and an object-subset `attributes` selector.

### Protocol 2.3

The transport-neutral command is `RuntimeCommand.SimulationAssert`. Tagged protocol JSON mirrors
the Java records, including tagged `RuntimeValue` and ID records:

```json
{
  "version": {"major": 2, "minor": 3},
  "requestId": "ball-awake-1",
  "sessionId": "game",
  "command": {
    "type": "simulationAssert",
    "assertion": {
      "assertionType": "propertyEquals",
      "entityId": {"value": "box2d.body.ball"},
      "property": "awake",
      "expected": {"valueType": "boolean", "value": true}
    },
    "evidenceRequirements": [],
    "executionEpochId": 0,
    "fromEpochTick": 1,
    "toEpochTick": 60,
    "evidenceLimit": 8
  }
}
```

The result tag is `simulationAssertion`. Its `result` contains `status`, `assertionType`, the exact
`scope`, optional `expected` and `observed`, bounded `evidence`, `evidenceIncomplete`, and `message`.
Each evidence item contains nullable `simulationTickId`/`frameId`, `executionEpochId`, positive
`epochTick`, `kind`, optional `entityId`/`property`, and optional `observed`. Missing correlation is
represented by absent IDs; it is never replaced with a fabricated frame.

Protocol 2.2 rejects `simulationAssert` with
`command requires protocol version 2.3`. Protocol 1.7 `assert` remains byte-for-byte unchanged and
frame-scoped.

### MCP request

MCP uses natural JSON values and a closed `runtime_simulation_assert` input. For an exact Box2D
contact begin:

```json
{
  "name": "runtime_simulation_assert",
  "arguments": {
    "sessionId": "game",
    "executionEpochId": 0,
    "fromEpochTick": 1,
    "toEpochTick": 60,
    "evidenceLimit": 8,
    "evidenceRequirements": [
      {"entityId": "box2d.contacts.main", "property": "complete"}
    ],
    "assertion": {
      "assertionType": "eventCount",
      "eventType": "box2d.contact.begin",
      "subject": "box2d.body.ball",
      "source": "box2d.body.ground",
      "attributes": {
        "worldId": "main",
        "key": {
          "fixtureAId": "ball-shape",
          "childIndexA": 0,
          "fixtureBId": "ground-shape",
          "childIndexB": 0
        }
      },
      "expectation": "AT_LEAST_ONE",
      "exactCount": 0
    }
  }
}
```

Every request object, assertion variant, nested area/vector, evidence requirement, and conjunction
term is closed. Unknown fields or assertion tags are rejected before evaluation. MCP flattens the
event selector to `eventType`, optional `subject`/`source`, and `attributes`; protocol JSON retains
the Java record's nested `selector` object.

Natural MCP JSON maps nulls, booleans, integers, decimals, strings, lists, and objects directly.
Exact enum and vector comparisons use reserved closed tags because a JSON string or object cannot
otherwise preserve the `RuntimeValue` type:

```json
{"$runtimeValue": "enum", "value": "DYNAMIC"}
{"$runtimeValue": "vector2", "x": 4.5, "y": 1.25}
```

The tag object accepts exactly the shown fields. `$runtimeValue` is reserved and cannot be an
ordinary assertion-object field. The MCP schema enforces the per-string and per-collection bounds;
the handler preflights the complete raw value before constructing a `RuntimeValue` and rejects more
than 16 levels or 1,024 total nodes. Selector inputs are likewise preflighted against their
four-level/32-node limit. An oversized or malformed tagged input returns `INVALID_QUERY`; it is not
evaluated as an ordinary object or silently normalized to another runtime type.

Treat `PASS` as a statement only about the selected registered evidence and exact requested ticks.
It is not proof of whole-program determinism, cross-platform Box2D callback equivalence, or semantic
causality such as landing, damage, or death.

## Compare deterministic Box2D runs

Use this recipe to repeat one registered scenario with the same seed, configuration, fixed step,
registered inputs, and exact tick count. It extends the existing determinism engine; it is not a
second replay mechanism. The application must register a fixed `SimulationTimelineSpec`, an
`acknowledgedTick` controller, an application dispatcher, and a deterministic scenario reset. The
reset must recreate or restore the Box2D world and rebind every selected adapter handle before its
new epoch baseline is captured. Do not leave an ordinary injected input queued when starting the
operation.

### Build and run a Box2D comparison in Java

`Box2dDeterminism` reads no native object. It compiles stable IDs, selected top-level properties,
exact world testimony, contact-completeness requirements, and scheduled registered inputs into the
JDK-only `SimulationDeterminismSpec`:

```java
long stepNanos = 16_666_667L;
SimulationDeterminismSpec spec = Box2dDeterminism.builder(
        "main",
        new Box2dDeterminism.WorldSettings(
                stepNanos, new Box2dVector(0.0, -9.8),
                4, true, true, true),
        "player-move", 7L,
        RuntimeValues.object(RuntimeValues.field("level", RuntimeValues.string("one"))),
        2, 60)
        .body("player", "position", "linearVelocity", "awake")
        .fixture("player-shape", "shapeType", "sensor", "categoryBits", "maskBits")
        .joint("player-joint", "jointType", "anchorA", "anchorB")
        .activeContacts()
        .contactEvents()
        .input(1, "move-right", RuntimeValues.object(
                RuntimeValues.field("pressed", RuntimeValues.bool(true))))
        .build();

SimulationDeterminismOperation queued = runtime.determinism().checkSimulation(
        spec, "player-move-repeat", Duration.ofSeconds(5));
// Continue servicing the application-owned dispatcher. Poll with the identical ID and spec.
SimulationDeterminismResult result = runtime.determinism().checkSimulation(
        spec, "player-move-repeat", Duration.ofSeconds(5)).result().orElseThrow();
```

Inputs are invoked through their existing closed `InputSpec` handlers immediately before the
selected epoch tick, after ordinary controlled-input processing and before the acknowledged tick
callback. Same-tick entries preserve caller order; the full ordered script is repeated for every
run. The comparison covers epoch ticks 1 through `ticksPerRepeat`; the reset baseline validates
configuration and selected registrations but is not itself compared.

`EQUAL` has no divergence. A `DIVERGED` result identifies the first differing epoch tick with both
monotonic session tick IDs, both execution epochs, both correlated runtime frames, and the existing
typed first difference:

```text
status: DIVERGED
epochTick: 183
leftSimulationTickId: 183
rightSimulationTickId: 366
leftExecutionEpochId: 41
rightExecutionEpochId: 42
leftFrameId: 902
rightFrameId: 1085
difference:
  kind: PROPERTY
  fact: box2d.body.player:position
  left:  {x: 4.155, y: 1.003}
  right: {x: 4.172, y: 1.003}
```

### Closed Java contract and bounds

The additive public records have these exact components:

| Record | Components |
| --- | --- |
| `SimulationDeterminismSpec` | `execution`, `inputs`, `configurationRequirements`, `evidenceRequirements`, `eventTypes` |
| `SimulationDeterminismInput` | `epochTick`, `inputId`, `parameters` (`ObjectValue`) |
| `SimulationConfigurationRequirement` | `entityId`, `property`, `expected` |
| `SimulationEvidenceRequirement` | `entityId`, `property` (must be an exact `true` boolean at every compared tick) |
| `SimulationDeterminismOperation` | `spec`, `requestId`, `command`, optional `result` |
| `SimulationDeterminismResult` | `status`, `message`, `profile`, optional `divergence`, `bounds`, optional `applicationFailure` |
| `SimulationDeterminismDivergence` | `epochTick`, left/right `simulationTickId`, left/right `executionEpochId`, left/right `frameId`, `difference` |

One request accepts at most 256 inputs, 32 configuration requirements, eight evidence requirements,
and 16 event types. Existing `DeterminismLimits`, request-value bounds, at-most-once request IDs,
timeouts, evidence byte limits, and operation retention apply unchanged. Configuration requirements
are exact facts checked before dispatch and again after every reset. Evidence requirements are
exact boolean completeness facts checked on every tick. Every selected entity must exist and be
untruncated, and every selected property name must exist on at least one selected entity; an empty
selection caused by a typo is rejected rather than reported equal.

### Protocol 2.4

The transport-neutral command tag is `simulationDeterminismCheck`. Protocol values use tagged
`RuntimeValue` objects and ID records exactly as shown:

```json
{
  "version": {"major": 2, "minor": 4},
  "requestId": "submit-player-repeat",
  "sessionId": "game",
  "command": {
    "type": "simulationDeterminismCheck",
    "determinismRequestId": "player-repeat",
    "spec": {
      "execution": {
        "scenarioId": "player-move",
        "randomSeed": 7,
        "configuration": {"valueType": "object", "fields": []},
        "repeatCount": 2,
        "ticksPerRepeat": 60,
        "deltaNanos": 16666667,
        "profile": {
          "comparisonScope": {
            "entityIds": [{"value": "box2d.body.player"}],
            "properties": ["position", "linearVelocity"],
            "excludedProperties": [],
            "includeEvents": false,
            "includeDecisions": false
          },
          "includeUiCorrelations": false
        }
      },
      "inputs": [{
        "epochTick": 1,
        "inputId": "move-right",
        "parameters": {"valueType": "object", "fields": [{
          "name": "pressed", "value": {"valueType": "boolean", "value": true}
        }]}
      }],
      "configurationRequirements": [{
        "entityId": {"value": "box2d.world.main"},
        "property": "fixedStepNanos",
        "expected": {"valueType": "integer", "value": 16666667}
      }],
      "evidenceRequirements": [{
        "entityId": {"value": "box2d.contacts.main"}, "property": "complete"
      }],
      "eventTypes": []
    },
    "timeoutNanos": 5000000000
  }
}
```

The result tag is `simulationDeterminism`; its `operation` uses the exact Java component names
above. The optional structured `applicationFailure` is projected beside the operation. Protocol
2.3 rejects this command with `command requires protocol version 2.4`; prior commands and result
shapes remain unchanged.

### MCP request

`runtime_simulation_determinism_check` uses bounded natural JSON values and registered
input-specific closed parameter schemas. Configuration requirements use the same reserved exact
enum/vector tags as simulation assertions:

```json
{"$runtimeValue": "enum", "value": "CONTINUOUS"}
{"$runtimeValue": "vector2", "x": 0, "y": -9.8}
```

The handler preflights the complete value before constructing immutable evidence: maximum depth
16, 1,024 total nodes, 256 items or fields per collection, and 4,096 code units per string.
Malformed tags and oversized values are rejected before dispatch.

Example request:

```json
{
  "name": "runtime_simulation_determinism_check",
  "arguments": {
    "sessionId": "game",
    "determinismRequestId": "player-repeat",
    "scenarioId": "player-move",
    "randomSeed": 7,
    "configuration": [{"name": "level", "value": "one"}],
    "repeatCount": 2,
    "ticksPerRepeat": 60,
    "deltaNanos": 16666667,
    "profile": {
      "comparisonScope": {
        "entityIds": ["box2d.body.player"],
        "properties": ["position", "linearVelocity"],
        "excludedProperties": [],
        "includeEvents": false,
        "includeDecisions": false
      },
      "includeUiCorrelations": false
    },
    "inputs": [{
      "epochTick": 1,
      "inputId": "move-right",
      "parameters": {"pressed": true}
    }],
    "configurationRequirements": [{
      "entityId": "box2d.world.main",
      "property": "fixedStepNanos",
      "expected": 16666667
    }],
    "evidenceRequirements": [{
      "entityId": "box2d.contacts.main", "property": "complete"
    }],
    "eventTypes": [],
    "timeoutNanos": 5000000000
  }
}
```

Every object is closed. Unknown request fields, unknown registered inputs, and unknown or mistyped
input parameters are rejected before dispatch. The tool appears only when a published runtime has
the fixed timeline, acknowledged controller, scenario, and command-dispatch capabilities required
to run it.

### Evidence honesty and claim boundary

The result is `INCONCLUSIVE`, never `EQUAL`, when any relevant baseline or tick has a missing
selected entity/property, configuration or rebind drift, a false/missing completeness requirement,
wrong epoch, failed/unknown/unacknowledged tick, executed-delta mismatch, missing resulting-frame
correlation, capture diagnostic, nested or frame truncation, partial eviction, timeout, callback
failure, or evidence-limit exhaustion. A preflight timing/solver/configuration conflict is rejected
before dispatch. The application must reset after any failure that leaves mutation unknown.

For selected events, the fixed `EXCLUDE_RUNTIME_IDENTIFIERS` normalization removes only
runtime-owned absolute correlation attributes named `executionEpochId`, `simulationTickId`, or
`runtimeFrameId` (along with the existing frame/event/decision identifiers). `epochTick`, contact
endpoints, impulses, and application semantic attributes remain exact comparison evidence. This
normalization changes only the comparable copy; inspected runtime events retain the full schema.

`EQUAL` means only that the explicitly selected immutable evidence matched under the same
application-reported setup in this operation. It is not whole-program determinism, semantic
causality, or a promise that another CPU, platform, libGDX version, or Box2D native version produces
identical floating-point state or callback order.

## Run the actual-native Box2D 3 inspection fixture

`Box2dInspectionFixtureTest` creates a real zero-worker Box2D 3 world, dynamic body, and genuine
capsule, registers stable runtime IDs, and verifies copied evidence contains no native scalar
identity. On Linux run it under Xvfb:

```text
xvfb-run -a ./gradlew :runtime-fixtures:test \
  --tests '*Box2dInspectionFixtureTest' --warning-mode=fail
```

The runtime module additionally covers a real revolute joint and stale, wrong-world, duplicate,
bounded, wrong-thread, and closed failures.
