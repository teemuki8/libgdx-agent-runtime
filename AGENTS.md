# libGDX Agent Runtime agent guide

Read this file before repository work. For task-specific workflow and verification, use the
repository skill at [`.agents/skills/libgdx-agent-runtime-dev/SKILL.md`](.agents/skills/libgdx-agent-runtime-dev/SKILL.md).

## Skills

- Use `$libgdx-agent-runtime-dev` for every implementation, review, architecture, protocol, MCP,
  fixture, documentation, dependency, or release task in this repository.
- Use `$karpathy-guidelines` when writing, reviewing, or refactoring code. Keep changes surgical,
  state assumptions, and define observable success before editing.
- Use `$github:github` for GitHub issue or pull-request orientation. Route unresolved review work
  to `$github:gh-address-comments`, failing Actions work to `$github:gh-fix-ci`, and an explicitly
  requested commit/push/draft-PR workflow to `$github:yeet`.
- Use `$skill-creator` when changing the repository skill itself. Keep its metadata aligned and run
  the skill validator before completion.

If an installed skill is unavailable in the active environment, follow its repository-equivalent
workflow rather than blocking ordinary development.

## Always-on contract

- This is a third-party Java 25 library. It does not own or patch libGDX, the application loop,
  render thread, input, assets, or disposal.
- Preserve module direction: `runtime-mcp -> runtime-protocol -> runtime-core <- runtime-libgdx`.
  `runtime-fixtures` may depend on the full stack but is never published.
- Keep `runtime-core` JDK-only. Do not add libGDX, Jackson, MCP, transport, filesystem, shell, or
  networking dependencies to core.
- Expose only facts explicitly registered by application code. Never add reflection, arbitrary
  object traversal or serialization, class-name input, expressions, scripts, bytecode inspection,
  inferred causality, or caller-selected network/filesystem access.
- Public values and evidence must be immutable, deterministic, deeply bounded, and safe across
  thread and trust boundaries. New collections, strings, queues, histories, manifests, diagnostics,
  requests, and responses need configured limits and explicit truncation or eviction evidence.
- The capture thread owns registration, lifecycle, frame capture, events, decisions, and explicit
  change attribution. Any thread may query completed immutable snapshots. Core creates no hidden
  scheduler or worker thread.
- `start()` captures baseline frame 0. Events and decisions require an open frame. Structural diffs
  have unknown cause unless application code explicitly supplies a semantic, event, or decision
  correlation.
- Preserve stable ordering, canonical `RuntimeValue` behavior, closed JSON/MCP schemas, typed errors,
  and bounded diagnostics without serialized stack traces.
- MCP is local stdio in an application-owned development launcher. Do not add a listener or imply
  that a separate JVM can inspect a live game without an explicit transport design and ADR.

## Change workflow

1. Inspect `git status --short` and preserve unrelated user changes.
2. Read the repository skill, then load only the docs and ADRs it routes to for the task.
3. Establish the public behavior and security boundary before editing. Add an ADR before a lasting
   architectural change.
4. Begin behavior changes with a focused test. For public features, trace one vertical slice through
   Java API, protocol, MCP, and the real fixture as applicable.
5. Make the smallest coherent change and update affected guides, protocol examples, release notes,
   or compatibility documentation.
6. Run a focused verification gate while iterating, then the relevant end-to-end gate from the skill.

## Definition of done

- Focused tests cover success, invalid lifecycle/thread use, hard bounds, truncation/eviction, stable
  ordering, and structured failure where relevant.
- Public Java records and methods validate and defensively copy inputs and have warning-free Javadocs.
- Protocol JSON and MCP inputs remain closed and reject unknown fields and unsupported versions.
- Linux-native integration changes pass the real LWJGL3 fixture under Xvfb.
- On Linux, treat `xvfb-run` as a repository verification prerequisite. Do not substitute the
  developer's active desktop display for the isolated fixture or full gate.
- Broad or release-facing work passes `./gradlew clean check javadoc --warning-mode=fail` (under Xvfb
  on Linux).
- Publishing to Maven Central is irreversible and requires explicit user authorization plus current
  Sonatype/public-artifact verification. Never infer publishing permission from ordinary release work.
