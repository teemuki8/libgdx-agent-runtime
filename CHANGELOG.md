# Changelog

All notable changes follow Keep a Changelog structure.

## [Unreleased]

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
