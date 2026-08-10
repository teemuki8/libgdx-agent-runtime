# Changelog

All notable changes follow Keep a Changelog structure.

## [Unreleased]

### Added

- Protocol 2.5 adds bounded replay-ready recording capture and execution from exactly one
  application-owned scenario or opaque checkpoint origin. Successful registered inputs are applied
  in stable tick order through acknowledged fixed-step control; selected immutable baseline/tick
  evidence reports `EQUAL`, first `DIVERGED`, or safe `INCONCLUSIVE` with explicit limits and
  sanitized application failures. Closed local-stdio MCP tools, a compiled workflow transcript,
  and real Box2D/LWJGL3 fixtures qualify the additive contract without changing recording schema 1.

- Protocol 2.6 adds one bounded application-dispatched operation that validates and executes an
  ordered sequence of explicit registered input transitions through exact acknowledged fixed
  ticks. Transitions sharing a local tick execute in request-list order; ticks without transitions
  still advance; persistent held controls remain application-owned. Execution is fail-stop with
  hard input/tick/evidence/retention/deadline limits, explicit completed/failed/not-executed
  transition testimony, no rollback or automatic retry, and never false `COMPLETED`. Recording
  schema 1 and replay consume the normal registered-input and controlled-tick evidence; failed,
  redacted, timed-out, or lifecycle-invalidated timeline evidence keeps replay inconclusive.
  Closed protocol 2.6 JSON, the `runtime_input_timeline` MCP tool with registered-input-specific
  scalar variants, a compiled workflow recipe, a tested MCP transcript step, and actual-native
  Box2D/LWJGL3 fixture coverage qualify the additive contract.

## [2.1.0] - 2026-08-10

### Added

- A JDK-only application-reported simulation timeline separates authoritative simulation ticks
  from render and runtime-capture frames. Session-monotonic and epoch-relative tick identity,
  fixed-step testimony, elapsed simulation time, tick-to-frame correlation, failure outcomes,
  bounded history, and closed protocol 2.1/MCP queries make mismatched or incomplete timing
  explicit (#65).

- The canonical application-owned fixed-step accumulator uses exact integer nanoseconds, bounded
  render delta and accumulated time, a catch-up limit, explicit dropped tick/time evidence, exact
  configured-step advancement while paused, and interpolation that never mutates authoritative
  state. A thin libGDX facade and closed protocol 2.2/MCP tools expose the same timeline without
  creating a loop, thread, timer, sleep, render call, or disposal responsibility (#66).

- The new published `agent-runtime-box2d` adapter exposes only explicitly registered worlds,
  bodies, fixtures, shapes, and joints under application-supplied stable IDs. Native values are
  copied into closed bounded immutable runtime entities, metres/radians and render-unit conversion
  remain explicit, and application code retains world, listener, stepping, rebind, and disposal
  ownership (#67).

- Explicit Box2D contact integration copies begin/end/pre-solve/post-solve callbacks immediately,
  publishes canonically ordered bounded events and active-contact tick snapshots, preserves
  phase-specific points/normals/impulses, and reports truncation, missing correlation, unmapped
  endpoints, reset, and listener lifecycle as typed incomplete evidence instead of treating it as
  proof that no contact occurred (#68).

- JDK-only simulation assertions evaluate immutable exact-tick evidence with scalar/vector
  tolerance, wrapped angles, areas, distance, events, list subsets, conjunctions, and every-tick
  semantics. `Box2dAssertions` adds data-only body/contact factories, while closed protocol 2.3
  and MCP preserve PASS / FAIL / INCONCLUSIVE results and never pass negative or temporal claims
  over incomplete evidence (#69).

- Additive exact-tick simulation determinism reuses the existing bounded repeated-scenario engine
  with scheduled registered inputs, exact reset-baseline configuration facts, per-tick completeness
  requirements, actual simulation-tick/frame divergence evidence, the data-only
  `Box2dDeterminism` builder, closed protocol 2.4, and
  `runtime_simulation_determinism_check`. Incomplete or truncated physics evidence cannot report
  equality (#70).

- An unpublished actual-native LWJGL3 + Box2D conformance fixture proves ball drop, dynamic
  collision, scheduled player movement, fixed-step render integration, exact controlled ticks,
  structured inspection/contacts/assertions, protocol and MCP access, deterministic selected-
  evidence reruns, render independence, and explicit timing/configuration failures under Xvfb.
  The agent cookbook and bootstrap migration guide point to the runnable example (#71).

- A non-published `runtime-examples` module now provides a tested agent cookbook, runnable hidden
  LWJGL3 state inspection, a complete application-owned controlled workflow, a same-JVM stdio MCP
  launcher with replayable closed transcript, and an actual-native deterministic Box2D consumer
  example. Documentation drift tests keep example paths, tools, versions, and the mandatory
  same-PR cookbook-update policy aligned (#73).

## [2.0.0] - 2026-08-08

### Added

- Removed entities remain queryable while retained history exists: the additive
  `AgentRuntime.entityHistory(EntityId, FrameRange, long versionOffset, int versionLimit)` returns
  an immutable `EntityHistoryPage` with newest-frame `current`, the bounded final pre-removal
  `finalRetainedState` snapshot (never synthesized), independent version cursors
  (`nextVersionOffset`/`hasMoreVersions`), partial-eviction and retained-frame-bound metadata, and
  a distinct `ENTITY_HISTORY_NOT_RETAINED` failure once every retained frame holding the entity is
  evicted. `EntitySnapshot`/`PropertyChange` shapes and the existing two-argument
  `entityHistory(EntityId, FrameRange)` remain frozen (#48).
- Protocol `2.0` (`ProtocolVersion.V2`, now `CURRENT`): an explicit `capability(version, command)`
  predicate enables every existing V1.13 command plus the additive `runtime_entity_history`
  command and `ENTITY_HISTORY_NOT_RETAINED` failure regardless of the zero minor, while
  protocols 1.0-1.13 keep their exact frozen shapes, error codes, and negotiation. Protocol 2.0
  responses also carry the structured `ApplicationFailureEvidence` from callback-bearing
  command, checkpoint, input, and determinism results; protocol 1.x projects only the
  642-bounded legacy envelope and never sanitized or raw detail (#48).

### Changed

- Documentation: state that stdio MCP hosting is exclusive (one stdio server per process) and
  describe the harness co-existence pattern via `ui_runtime_compare` (#37).
- Documentation: add the frame-correlation contract guide covering `UiFrameCorrelation`,
  one-correlation-per-frame recording, token pairing, and the `ui_runtime_compare` loop-order
  requirement; document the token contract on the `UiFrameCorrelation` Javadoc (#38).

### Security

- Response byte limits are now enforced during serialization instead of after: `ProtocolJson`
  streams every response through a hard-cap sink so serialization aborts with `LIMIT_EXCEEDED`
  before byte `MAX_RESPONSE_BYTES + 1` is retained, `ProtocolJson.encode` reuses that same
  bounded writer, and the MCP stdio transport serializes each actual `JSONRPCMessage` exactly
  once into a bounded buffer and writes those exact checked bytes plus the newline. An
  oversized outbound message becomes one bounded typed JSON-RPC error (`code -32001`, echoing
  the request id) with no partial prefix, never materializes an unbounded intermediate array,
  and does not terminate later requests (#49).
- Stdio JSON-RPC frames are now bounded before parsing: a fixed `ProtocolJson.MAX_REQUEST_BYTES + 1`
  byte framer replaces `BufferedReader.readLine`, strict-UTF-8 decoding (`CodingErrorAction.REPORT`)
  rejects malformed input instead of silently replacing it, and the MCP mapper enforces the protocol
  codec's depth 32 / string 16,384 / number 128 stream constraints on stdio. Oversized and malformed
  frames are drained through their newline, answered with one bounded null-id parse error, and do not
  terminate later valid requests; only an unterminated frame at EOF ends the reader exceptionally
  without parsing (#44).
- Application callback failures expose structured `ApplicationFailureEvidence` (stable category,
  exception class, session-prefixed correlation identifier such as
  `sessionId|failure-N`, optional sanitized detail) instead of raw exception messages or stack
  traces. Protocol 1.0-1.13 responses keep their exact legacy wire fields and render only the
  642-character envelope (`correlationId|category|exceptionClass`); sanitized detail is bounded at
  1_024 code units, appears only in the structured field, and a throwing sanitizer fails closed
  with raw throwables routed to non-stdout local logging only (#50).

### Fixed

- Determinism evidence limits are now enforced before retention instead of after allocation:
  each frame's selected entities and facts are counted incrementally with bounded iteration and
  admission stops at the first item whose cumulative observed total would exceed
  `maximumEntitiesPerFrame` or `maximumFactsPerFrame` (the over-limit item is not sized and no
  later entity, property, event, decision, or UI correlation is visited; observed counters
  saturate at those maxima). A frame is retained only when its exact canonical type-tagged
  encoded byte size plus the retained total stays within `maximumEncodedEvidenceBytes`,
  computed without `toString()` or temporary byte arrays. Capture stops immediately with a
  specific `INCONCLUSIVE` reason, no later tick or scenario reset runs, and reported encoded
  bytes never exceed the configured maximum (#43).
- A throwing simulation-control stop condition now records `CALLBACK_FAILED` completion
  evidence before the typed command failure propagates: a terminal failed wait retains
  `CALLBACK_FAILED` (never `PENDING`) as its stop reason, completed-tick and frame evidence
  stays accurate through first and later condition checks with idempotent polling, and the
  original throwable still reaches command failure classification so the structured
  `command.failed` evidence and its sanitized diagnostics are retained without leaking raw
  messages (#47).
- Open-frame events, decisions, and explicit change causes are now bounded at insertion:
  `emit` retains at most `retainedEvents`, `beginDecision` at most `decisionsPerFrame`, and the
  new `FrameStagingLimits.causesPerFrame` caps `causeNextChange` map entries. Excess facts only
  advance saturating observed counters — dropped events still receive monotonic event IDs but
  their attributes are never read or copied, overflow decisions return the no-retention disabled
  scope (a retained open decision still rejects nesting), and dropped causes skip map retention
  while key validation still occurs. Per-frame counters reset at frame completion and truncation
  evidence reports the saturating observed count with the already-bounded retained count (#45).
- Dynamic entity sources are now enumerated sequentially on the capture thread through
  `stream.sequential().iterator()` with a hard global bound: exactly the first
  `entitiesPerSnapshot` non-null observations form the bounded prefix, and the next non-null
  observation only counts as the truncation sentinel before enumeration stops across all sources.
  Parallel application streams cannot escape the capture thread, infinite or very large sources
  cannot hang capture, the sentinel is never materialized or diagnosed, and truncation reports
  the sentinel-based observed count deterministically (#40).
- Runtime lifecycle `status()` is now backed by a `volatile` field and reads safely from any
  thread without a monitor; capture-thread-only monotonic transitions are unchanged (#42).
- Closing the runtime now releases every callback-bearing registry: scenario reset handlers,
  action handlers, simulation control callbacks and condition predicates, input handlers and
  queued/scheduled injections, checkpoint providers and opaque handles, pending command closures,
  and pending operations, while immutable catalogs (including completed checkpoint descriptors),
  completed history, and terminal evidence stay queryable. Every registry close hook runs even
  when an earlier hook fails, later failures are attached as suppressed, `CLOSED` is always
  published, and the first failure is rethrown. Closing is atomic with in-flight submissions, new
  submissions reject with `RUNTIME_CLOSED`, and repeated `close()` is a no-op (#41).
- Checkpoint replacement is now failure atomic: the new handle is created before the oldest
  retained checkpoint is evicted, the retained entry is installed and the chosen old entry removed
  in one synchronized block, and only the evicted handle is disposed afterward. A failed
  replacement keeps every prior descriptor/handle listed and restorable with no disposal, an
  installation failure disposes only the newly created handle while preserving the original
  registry and suppressing its cleanup failure in favor of the primary command failure, and a
  post-install eviction disposal failure never fails the committed create (#46).

### Compatibility notes (2.0)

- Breaking: `CaptureDiagnostic` replaces its `exceptionClass` and `message` components with one
  `ApplicationFailureEvidence failure` component (`exceptionClass()` becomes
  `failure().exceptionClass()`, `message()` becomes `failure().legacyEnvelope()`).
- Breaking: `CommandStatus`, `CheckpointOperation`, `InputInjection`, and `DeterminismResult` gain
  an `Optional<ApplicationFailureEvidence> applicationFailure` final component while retaining
  their `diagnostic`/`message` fields, which hold the 642-character legacy envelope for callback
  failures and their previous text for safe runtime-owned reasons.
- Breaking: the four diagnostic-text limits (`RuntimeLimits.stringLength`,
  `CommandDispatchLimits.diagnosticLength`, `CheckpointLimits.descriptionLength`,
  `InputLimits.stringLength`) now require a 642-character minimum;
  `CommandDispatchLimits.developmentDefaults()` and `CheckpointLimits.developmentDefaults()`
  raise their diagnostic bounds from 512 to 1_024.
- Breaking: determinism callback-failure messages are the bare legacy envelope with no prefix.

## [1.0.0] - 2026-08-04

First production-supported release and stable 1.x compatibility baseline.

### Added

- Frozen protocol 1.0 compatibility plus opt-in protocol 1.1 capability metadata.
- Protocol 1.2 bounded application-owned command dispatch with request deduplication, cancellation,
  timeouts, retention, and structured completion evidence.
- Protocol 1.3 execution epochs and explicit reset baselines with epoch-scoped completed-frame
  queries.
- Protocol 1.4 registered resettable scenarios with idempotent application-thread dispatch.
- Protocol 1.5 explicit fact attribution and closed metadata query filters.
- Protocol 1.6 typed semantic actions with validated closed parameters and correlated completion
  evidence.
- Protocol 1.7 closed declarative assertions with bounded PASS, FAIL, and INCONCLUSIVE evidence
  across the Java API, typed protocol, MCP, and deterministic fixture.
- Protocol 1.8 application-owned pause, resume, exact tick advance, and bounded semantic/assertion
  waits with completed-frame evidence through Java, protocol, MCP, and the deterministic fixture.
- Protocol 1.9 explicit registered input catalogs and bounded controlled-tick injection with closed
  MCP schemas, at-most-once correlation, redaction, and tick/epoch/frame evidence.
- Protocol 1.10 application-owned opaque checkpoint creation and restore with bounded retention,
  cleanup callbacks, closed MCP tools, and completed restore-baseline evidence.
- Protocol 1.11 explicit bidirectional runtime/UI bindings and frame mappings with stale,
  ambiguous, truncation, and retention-eviction evidence through Java, protocol, MCP, and fixture.
- Protocol 1.12 bounded input/execution recording with versioned immutable manifests, validated
  semantic-action parameters, optional scenario/checkpoint/seed/configuration metadata,
  deterministic chunk retrieval, exact canonical encoded-byte accounting, and explicit
  item/tick/duration/size/retention loss evidence across Java, protocol, MCP, and fixture.
- Protocol 1.13 bounded repeated-scenario determinism comparison with application-acknowledged
  seed/configuration reset, exact controlled ticks in separate execution epochs, configurable
  observables, stable first-divergence evidence, and explicit inconclusive/truncation/eviction
  evidence across Java, protocol, MCP, and the deterministic fixture.
- Deterministic optional-control conformance coverage across public Java, protocol JSON, closed MCP
  schemas, application-thread dispatch, and the real Xvfb-backed LWJGL3 lifecycle fixture.

## [0.1.0] - 2026-07-30

Initial Java 25 multi-module preview with the bounded immutable runtime model, capture, diffs,
events, decisions, protocol, MCP, and fixture. No production-stability guarantee.
