# Native Box2D fixture design

Issue #71 is a final qualification slice, not a new physics abstraction. One reusable fixture model
will own an actual Box2D `World`, stable registration handles, contacts, a
`LibGdxFixedStepSimulation`, and a started `AgentRuntime`. It exposes only bounded test operations.

The model preserves stable agent-visible IDs across resets. Reset creates the requested scenario's
replacement native objects, closes descendant fixture/body registrations, rebinds the world,
registers the replacement descendants under the same IDs before the scenario baseline, reinstalls
the explicit contact listener, and clears the fixed-step accumulator. The three scenarios are ball
drop, two-body collision, and player movement against static geometry.

The acknowledged fixed-step callback performs contact capture around exactly one `World.step`, then
post-physics game logic. Registered inputs mutate only explicit fixture intent before that callback.
Normal render updates and paused `advanceFixed` therefore share the simulation timeline and capture
path. Interpolation alpha is presentation-only.

Tests divide responsibility without weakening the vertical slice:

- actual-native Java tests drive scenario reset, scheduled input, exact ticks, inspection,
  assertions, determinism, and deliberate failures;
- protocol/MCP calls query or execute the same live fixture in-process;
- a hidden LWJGL3 application uses `Gdx.graphics.getDeltaTime`, application dispatch, and rendering,
  then writes compact structured evidence for the Xvfb smoke test.

All negative evidence remains typed: delta mismatch, catch-up/accumulator drops, suspicious scale,
unregistered endpoints, truncation, assertion inconclusive, and deterministic divergence. No test
uses a screenshot as proof.
