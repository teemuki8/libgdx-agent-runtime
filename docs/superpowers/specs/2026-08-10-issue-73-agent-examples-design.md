# Issue 73 agent examples design

## Status and scope

GitHub issue #73 is the user-approved product contract. This design selects the implementation
shape for its remaining work after issues #65-#71. The existing cookbook, prominent links, and
mandatory cookbook-update policy are retained and expanded rather than replaced. Frozen release
notes are not rewritten, no published artifact is added, and no Maven Central action is authorized.

## Decision

Add a dedicated, non-published `runtime-examples` Gradle module. Its main sources compile as an
ordinary external consumer of the five published modules; it cannot use package-private runtime or
fixture APIs. `runtime-fixtures` remains qualification scaffolding and does not become an example
dependency.

This is preferable to adding more sources to `runtime-fixtures`, because agents need small
standalone integrations rather than test-oriented helpers. Extracting Markdown snippets into
synthetic compilation units was rejected because it would obscure imports, lifecycle, and native
ownership while still not producing runnable applications.

## Example applications

The module contains four bounded examples under
`io.github.teemuki8.libgdx.agent.runtime.examples`:

1. `BasicInspectionApplication` is a hidden-capable LWJGL3 `ApplicationAdapter`. It constructs the
   runtime on the render thread, explicitly registers one entity, starts baseline capture, emits a
   semantic event inside a frame, queries immutable evidence, and closes on the owner thread.
2. `ControlledWorkflowExample` is an application-owned deterministic model with an explicit
   dispatcher queue. It demonstrates queued submission and polling, scenario reset, pause,
   scheduled input, exact acknowledged ticks, assertions, checkpoint restore, recording, and
   selected-evidence determinism without hidden scheduling.
3. `SameJvmMcpApplication` publishes that runtime and owns one stdio `RuntimeMcpServer` inside the
   same LWJGL3 process. Stdout is reserved for MCP, logging goes to stderr, and close order is
   server, publication, runtime.
4. `DeterministicBox2dApplication` is a consumer-oriented fixed-step Box2D sample with explicit
   stable world/body/fixture IDs, listener composition, scenario recreation/rebinding, scheduled
   movement, assertions, deterministic comparison, and application-owned native disposal. It is
   smaller than the conformance fixture and links to structured failure recipes instead of
   embedding fault injection in production example code.

Examples return small immutable result records for tests; they do not add runtime abstractions or
claim that the library owns the application loop.

## Tested transcripts and drift checks

Machine-readable JSON assets in `runtime-examples/src/main/resources/transcripts` contain complete
MCP tool name/argument/result samples for discovery, reset, pause, input, configured-step advance,
entity/event inspection, assertion, and simulation determinism. Tests load every asset, reject
unknown fields through the actual tool handler, and compare the declared tool names with the
advertised catalog. Representative protocol requests/results round-trip through protocol 2.4.

One documentation contract test also checks that:

- every cookbook/example link exists;
- current dependency snippets use published `2.0.0` or the explicit `2.0.1-SNAPSHOT` development
  boundary as appropriate;
- `runtime-examples` is absent from the root published-module set;
- every public example named in the cookbook exists; and
- the root guide and repository skill retain the cookbook-update rule.

The JSON assets are illustrative closed calls, not a second protocol implementation. Runtime tests
remain authoritative for exact Java records and schemas.

## Cookbook organization

Prepend a task index and add general recipes before the existing fixed-step/Box2D chapters:

- instrument and inspect state;
- query changes, events, and decisions;
- reset, control, schedule input, checkpoint, assert, record, compare, and correlate UI evidence;
- host same-JVM stdio MCP;
- diagnose queued, failed, unknown, evicted, truncated, and `INCONCLUSIVE` results.

Each recipe links to a compilable example or transcript and states prerequisites, exact call,
representative result, lifecycle/thread rule, relevant bound, cleanup owner, and one incorrect
approach. The cookbook distinguishes absence from incomplete evidence and never presents a
screenshot as authoritative state.

## Verification boundary

Focused tests begin RED before each example implementation. Linux verification runs example tests
and both LWJGL3/Box2D native samples under Xvfb. Final verification is the repository `check` and
`full` gates plus `jdeps` proving `runtime-core` remains `java.base` only. CI compilation on macOS
and Windows qualifies source/schema portability; cross-platform Box2D floating-point equality is
not claimed.
