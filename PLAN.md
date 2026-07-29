# Implementation plan

## Assumptions

- V1 uses Java 25, libGDX 1.14.2, Jackson 2.22.1, and the Java MCP SDK 2.0.0,
  matching the adjacent project's current qualified dependency line.
- Frame zero is an explicit baseline capture made by `start()`. The first user frame can therefore
  report changes from registered state before the callback.
- Events and decisions are accepted only while a frame is open. There is no ambiguous between-frame
  queue.
- Capture, registration, unregistration, events, and decision mutation belong to one capture thread.
  Completed immutable history is safe to read from any thread.
- A fixture-only module is not published.
- Artifact-name availability was checked on 2026-07-29 and is not a reservation.

## Milestones

- [x] Audit the empty workspace and `teemuki8/libgdx-ui-harness`.
- [x] Record four architectural decisions before production implementation.
- [x] Milestone 0: Gradle foundation, CI, policy files, and green baseline.
- [x] Milestone 1: immutable core model and validation.
- [x] Milestone 2: capture lifecycle, registration, diffing, retention, and queries.
- [x] First vertical slice through direct Java API.
- [x] Milestone 3: libGDX adapter and real LWJGL3 fixture.
- [x] Milestone 4: protocol, JSON hardening, registry, and round trip.
- [x] First vertical slice through protocol.
- [x] Milestone 5: eight MCP tools and stdio lifecycle.
- [x] First vertical slice through MCP.
- [x] Milestone 6: guides, release candidate documentation, and full verification.

## Verification

- Focused module tests after each behavior slice.
- `xvfb-run -a ./gradlew :runtime-fixtures:test` for the native fixture.
- `./gradlew clean check javadoc --warning-mode=fail` before completion.
- Inspect generated Javadoc warnings and the published-module dependency graph.

## Verification completed

- `./gradlew clean check javadoc --warning-mode=fail`
- `./gradlew :runtime-fixtures:test --tests '*Lwjgl3FixtureSmokeTest' --rerun-tasks`
- `./gradlew check --write-locks --warning-mode=fail`
