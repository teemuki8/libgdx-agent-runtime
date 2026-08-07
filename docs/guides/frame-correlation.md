# Frame correlation: making runtime values visible to the harness

The runtime exposes registered entities, properties, events, and decisions to Java tests and
coding agents. When a game also embeds [`libgdx-ui-harness`](https://github.com/teemuki8/libgdx-ui-harness),
runtime values become visible to the harness only through an explicit per-frame correlation:
the application records one `UiFrameCorrelation` per rendered frame under a stable token, the
harness-side binding carries that token, and the harness `ui_runtime_compare` tool compares the
displayed UI value against the runtime value on a proven frame.

The harness never guesses. Without a provable correlation it reports `UNCORRELATED`, `STALE`, or
`UNAVAILABLE` — never an inferred frame. This guide documents the runtime side of that contract
and points at the compilable reference in the markup preview.

## Why a correlation record exists

`UiFrameCorrelation` maps one completed runtime frame to a UI frame identifier or a shared
correlation token:

```java
public record UiFrameCorrelation(
        ExecutionEpochId runtimeEpochId,
        FrameId runtimeFrameId,
        String uiSessionId,
        Optional<String> uiFrameId,
        Optional<String> correlationToken)
```

A correlation requires a UI frame id or a shared token, never both absent. All identifiers are
bounded, nonblank strings of at most 256 UTF-16 code units. The record does not assume numeric
equality between the two clocks — the runtime frame counter and the harness clock are independent,
and the correlation is the only explicit bridge between them.

The harness `AgentRuntimeObservationSource` resolves each binding against the latest completed
runtime frame and reports an observation only when a recorded correlation proves the harness
frame: the correlation's token matches the binding and its runtime frame matches the value's
frame. There is no clock fallback and no frame guessing.

## Recording one correlation per frame

Record one correlation per rendered frame, on the capture thread, after the frame completes:

```java
// on the capture thread, after runtime.endFrame()
runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
        runtime.currentEpoch(),
        runtime.latestFrame().orElseThrow().frameId(),
        uiSessionId,
        Optional.of(Long.toString(harnessFrameNumber)),
        Optional.of(CORRELATION_TOKEN)));
```

Rules:

- **One per rendered frame.** The correlation proves that a specific runtime frame and a specific
  UI frame were the same rendered frame. Skipping a frame, or recording several, weakens or breaks
  the proof.
- **Capture thread, outside an open frame.** `recordFrame` requires the capture thread and rejects
  calls while a `runtime.frame(...)` callback is open. In a wrapped loop, call it after
  `runtime.endFrame()`.
- **Stable, non-secret token.** The token is shared between the runtime correlation and the
  harness-side binding; it is an association key, not a credential. Use one constant per
  application (the markup preview uses `markup-preview-frame`). A token mismatch is silent
  degradation — the comparison reports `UNCORRELATED`/`STALE` rather than an error, so keep the
  token stable and spelled identically on both sides.
- **Bounded retention.** Frame mappings are retained under configured limits with eviction
  evidence; `runtime_ui_frames` reports `evictedCount` when retention evicts old correlations.

## Pairing the token with harness-side bindings

The runtime side registers the explicit binding between a runtime entity/property and a semantic
UI control:

```java
runtime.uiCorrelations().register(new UiBinding(
        "markup:user:value",
        EntityId.of("user"),
        Optional.of("value"),
        uiSessionId,
        controlId,
        UiBindingValidity.always()));
```

The harness side attaches the same token to the actor's binding through the harness `Semantics`
facade (`Semantics.bind(actor, ...)`), or automatically via the markup `HarnessSemanticSink`
when actors declare `data-runtime-entity` / `data-runtime-property` attributes:

```java
// harness side, markup HarnessSemanticSink
semantics.bind(actor, new RuntimeBinding(
        entityId, propertyId, null, null, CORRELATION_TOKEN));
```

`AgentRuntimeObservationSource` (harness `harness-agent-runtime` module) consumes the runtime
through the published core API: it resolves each binding against `runtime.latestFrame()` and
`runtime.uiCorrelations().framesForUiSession(...)`, and reports an observation only when the
token and runtime frame match a recorded correlation.

## The ui_runtime_compare path

The harness serves `ui_runtime_compare` when the session declares the runtime-compare
capability. `PreviewMcp` (markup preview) wires the `RuntimeCompareCoordinator` to run the pure
`RuntimeComparator` on the render-thread scheduler.

Loop order matters for `EQUAL`. The preview drains render-thread scheduler commands **then**
advances the clock:

```java
void beforeDraw() {
    scheduler.drain();
    clock.advance(FIXED_STEP);
}
```

With that order the comparator's snapshot frame equals the last recorded correlation frame, which
is the condition for `EQUAL`. Advancing the clock before draining commands lets the snapshot frame
drift from the recorded correlation, and the comparison degrades to `STALE`/`UNCORRELATED`.

## Reference implementation

The markup preview (`libgdx-ui-markup` repository, `libgdx-ui-markup-preview` module) is the
stack's only module wiring runtime + harness + markup together:

- `PreviewMcp.java` constructs the harness server, the `AgentRuntimeObservationSource`, the
  `RuntimeComparator`, and records one `UiFrameCorrelation` per frame under
  `markup-preview-frame`.
- `MarkupRuntimeSource.register(...)` registers `data-runtime-entity` actors as runtime value
  sources plus `UiBinding`s.
- `HarnessSemanticSink` binds the same actors into the harness `Semantics` facade with the
  correlation token.
- `MarkupHarnessEndToEndTest.markupRuntimeEntityComparesThroughHarnessMcp` drives a real fill
  through the harness MCP and asserts `EQUAL` (see the markup repo's ADR 0002 for the full
  decision and status semantics).

## Runtime-only tools

For runtime-only launchers, the correlation evidence is also queryable directly through the
runtime MCP surface: `runtime_ui_bindings` resolves bindings either direction
(entity/property → UI controls, or UI session/control → runtime state) and `runtime_ui_frames`
exposes the recorded frame mappings by UI session or token. Both are read-only, use protocol 1.11,
and appear in the catalog only when explicit binding or frame evidence is registered. The
`runtime_determinism_check` profile may also include the recorded correlations in its comparison
scope via `includeUiCorrelations`.
