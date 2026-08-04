# Changelog

All notable changes follow Keep a Changelog structure.

## [Unreleased]

### Added

- Initial Java 25 multi-module release-candidate implementation.
- Bounded immutable runtime model, capture, diffs, events, decisions, protocol, MCP, and fixture.
- Frozen protocol 1.0 compatibility plus opt-in protocol 1.1 capability metadata.
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

## [0.1.0] - Unreleased

First planned public milestone. No production-stability guarantee.
