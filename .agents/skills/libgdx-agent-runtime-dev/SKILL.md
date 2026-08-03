---
name: libgdx-agent-runtime-dev
description: Repository workflow for libgdx-agent-runtime. Use when implementing, reviewing, debugging, documenting, testing, or releasing changes to runtime-core, runtime-libgdx, runtime-protocol, runtime-mcp, runtime-fixtures, the public Java API, frame capture, bounded values, JSON commands, MCP tools, libGDX integration, dependencies, or Maven Central artifacts.
---

# libGDX Agent Runtime development

Start with the repository-root `AGENTS.md`. Preserve its architecture and security contract.

## Select context

Read only the sources relevant to the task:

- Core lifecycle, frames, diffs, retention, or queries: `docs/design-contract.md`,
  `docs/adr/0002-explicit-semantic-instrumentation.md`,
  `docs/adr/0003-bounded-immutable-runtime-model.md`, and the matching core tests.
- Capture/render-thread behavior or libGDX helpers: `docs/adr/0004-render-thread-capture.md`,
  `docs/guides/getting-started.md`, and `runtime-libgdx` plus fixture tests.
- Entity/event/decision instrumentation: `docs/guides/instrumenting-game-state.md` and
  `docs/guides/decision-tracing.md`.
- Protocol, JSON, publication registry, or MCP: `docs/guides/agent-tools.md`, `SECURITY.md`,
  `RuntimeCommand`, `ProtocolJson`, `RuntimeProtocolService`, `RuntimeToolCatalog`, and their tests.
- Dependency or build changes: `docs/dependency-review.md`, version catalog, dependency locks,
  verification metadata, CI, and publication archive checks.
- Release preparation: `docs/guides/releasing.md`, `docs/sonatype-central-compliance.md`, and the
  latest release notes. Re-check current external requirements before release work.

Source and tests are authoritative when documentation has drifted. Update the affected contract or
guide in the same change.

## Work vertically

1. Inspect repository status and the current public contract.
2. Identify the owning module. Do not route dependencies against the module direction.
3. Add the smallest focused regression or contract test first.
4. Implement immutable validated core behavior before adapters or transports.
5. For a public feature, carry the same semantics through Java API, typed protocol, MCP catalog and
   handler, and `runtime-fixtures`; do not implement only one access layer.
6. Exercise invalid lifecycle/thread calls, unknown fields, unsupported versions, bounds,
   truncation/eviction, deterministic ordering, and bounded diagnostics as applicable.
7. Update guides and ADRs when public behavior or architecture changes.

Do not weaken tests to accept nondeterminism. Do not infer causality from adjacent frames or generic
object state. Keep application-owned scheduling and mutation explicit.

## Verify

Run `.agents/skills/libgdx-agent-runtime-dev/scripts/verify.sh <gate>` from the repository root:

- `core`, `libgdx`, `protocol`, or `mcp`: focused module tests and Javadocs.
- `fixture`: real LWJGL3 fixture on Linux under Xvfb; compile fixture tests on other systems.
- `check`: repository checks and published-module Javadocs without cleaning.
- `full`: clean end-to-end gate; require `xvfb-run` on Linux and follow hosted CI behavior on other
  systems.

On Linux, install `xvfb-run` before using `fixture` or `full`. Use package
`xorg-x11-server-Xvfb` on Fedora/Nobara or `xvfb` on Debian/Ubuntu. Do not bypass the gate with the
active desktop `DISPLAY`.

Use a focused gate while iterating. Use `check` for cross-module code or public API changes and
`full` for broad, native-integration, dependency, packaging, release-facing, or explicitly requested
verification. Report exactly what ran and any skipped platform-native coverage.

## Release boundary

Treat signing, staging, publishing, tag creation, and public-release changes as separate steps.
Publishing to Maven Central requires explicit user authorization. Verify the authoritative Sonatype
state and public artifacts before reporting publication complete.
