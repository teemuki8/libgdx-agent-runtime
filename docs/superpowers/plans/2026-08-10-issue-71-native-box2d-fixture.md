# Issue #71 implementation plan

1. Integrate the #66 fixed-step and #67-#70 physics branches, preserving both public contracts and
   allocating additive protocol versions 2.2 through 2.4 in dependency order.
2. Add RED actual-native tests for ball drop, collision, player input, exact tick/frame evidence,
   assertions, repeated determinism, rendering independence, and explicit failure diagnostics.
3. Implement one application-owned reusable Box2D scenario fixture with stable rebinds and the
   canonical libGDX fixed-step callback.
4. Extend the hidden LWJGL3 smoke application and Xvfb test to qualify dispatch/render thread,
   running updates, controlled ticks, contacts, and render-independent evidence.
5. Exercise typed protocol 2.3/2.4 and closed MCP paths against the same live fixture.
6. Add the bootstrap migration guide and update the roadmap, design contract, cookbook, tool,
   dependency, and release documentation.
7. Run focused native tests, fixture/full repository scripts under Xvfb, clean check/Javadoc,
   dependency locks/metadata checks, and `jdeps` for JDK-only core; then open a stacked draft PR.
