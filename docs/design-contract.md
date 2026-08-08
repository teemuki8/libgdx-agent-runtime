# V1 behavioral contract

This document makes lifecycle and evidence edge cases explicit.

1. An event receives the currently open frame ID synchronously in `emit`.
2. Events between frames are rejected with `INVALID_LIFECYCLE`; there is no hidden queue.
3. `start()` captures baseline frame 0 and produces no property-added changes.
4. A disappeared entity produces one `ENTITY_REMOVED` change; its prior snapshots remain in
   history until frame eviction.
5. Static IDs are unique at registration. Across dynamic sources, static wins, then source name
   order; later duplicates are omitted with a diagnostic.
6. Property failures omit that property and retain provider, entity, property, and structured
   `ApplicationFailureEvidence` (category, exception class, session-prefixed correlation
   identifier, optional sanitized detail) without a stack trace. Raw application exception
   messages are never exposed by default; an application-owned sanitizer may opt into bounded
   public detail that appears only in the structured field and never in the legacy 642-character
   envelope (`correlationId|category|exceptionClass`) rendered by protocol 1.x projections.
   Diagnostic-text limits (`RuntimeLimits.stringLength`, `CommandDispatchLimits.diagnosticLength`,
   `CheckpointLimits.descriptionLength`, `InputLimits.stringLength`) validate a 642 minimum so the
   envelope always fits.
7. `NaN` and infinity are rejected. Decimals use canonical finite `BigDecimal`, at most 128 digits
   of precision and bounded scale.
8. Entities sort by ID and properties/attributes/object fields sort by name.
9. Registration and unregistration run on the configured capture thread outside an open frame.
10. Any thread may query completed immutable snapshots.
11. Evicted frames and their events leave query results; page metadata reports a partially evicted
    requested range and current oldest/newest retained frames.
12. Decision scopes cannot nest in V1.
13. A scope open at frame end, or any decision in a failing frame callback, is `ABORTED`.
14. Snapshot diffs default to `UNKNOWN`. `causeNextChange` can associate a semantic code, exact
    event ID, or exact decision ID with the next observed entity/property difference.
15. After close, completed retained history and `CLOSED` status remain; live providers are released.
16. Protocol `1.0` remains frozen, protocol `1.1` adds extension-aware capability metadata, and
    protocol `1.2` adds application-command status and pre-dispatch cancellation. Any other exact
    version returns `PROTOCOL_VERSION_UNSUPPORTED` before command execution.
17. Capture truncations contain dimension/observed/retained/limit. Query pages separately contain
    `hasMore` and retention-range metadata.
18. Only explicit `RuntimeValue` providers cross the boundary. No reflection, object traversal,
    arbitrary serializer, class-name input, or generic object value exists.
19. Application commands are bounded, deduplicated while their request IDs are retained, and run
    only through an explicitly registered application dispatcher on the capture thread. The
    runtime creates no command thread, timer, game loop, or scheduler.
20. Every frame identifies an execution epoch. Frame zero is the `INITIAL` baseline of epoch zero;
    successful reset/restore baselines increment the epoch and frame ID without diffing across the
    discontinuity. Protocol `1.3` exposes bounded epoch-filtered frame queries.
21. Resettable scenarios are application-owned callbacks registered under stable IDs with optional
    bounded descriptions. Resets run through the application dispatcher, start a new
    `SCENARIO_RESET` epoch, return its completed baseline frame, and never depend on reflection or
    arbitrary object serialization.
22. Fact attribution is optional application testimony. Subsystem and correlation filters are exact;
    source-location labels are bounded and unverified. The runtime never infers metadata, and event
    source entities remain distinct from source subsystems.
23. Semantic actions have explicitly registered closed scalar schemas and application-owned handlers.
    Inputs are validated before capture-thread dispatch; retained retries do not re-execute, results
    carry bounded command/frame evidence, and effects are correlated only by explicit testimony.
24. Declarative assertions evaluate only completed immutable evidence in one explicit bounded epoch
    range. The assertion union and nested comparison scope are closed; negative and remains
    assertions require complete evidence, while eviction, capture diagnostics, aborted decisions,
    or relevant truncation produce `INCONCLUSIVE` instead of a misleading pass or failure.
25. Simulation control is an optional application-owned controller registered explicitly before
    start. Pause/resume callbacks gate only normal application updates; exact ticks and bounded waits
    run through application command dispatch on the capture thread, capture one completed frame per
    tick, and retain requested/completed counts, first/final frame IDs, and explicit stop reasons.
    The runtime owns no loop, scheduler, sleep, or inferred condition.
26. Input injection is limited to explicitly registered stable IDs and closed scalar schemas.
    Requests target the next or a bounded future controlled tick while paused, execute in acceptance
    order on the application-owned command/capture thread, and retain at-most-once tick, epoch, frame,
    redaction, and diagnostic evidence. The runtime installs no global or operating-system input hook.
27. Checkpoints exist only when an application registers create, restore, and disposal callbacks.
    The runtime retains bounded descriptors plus opaque handles; handles and payloads never cross the
    Java inspection, protocol, or MCP boundary. Creation is anchored to the latest quiescent completed
    frame. Restore runs through application dispatch and claims success only after one new
    `CHECKPOINT_RESTORE` baseline completes; failures report possible partial application mutation.
28. Runtime-to-UI correlation exists only through explicit application-registered immutable
    bindings and frame mappings. Binding validity may name an execution epoch, runtime-frame range,
    and UI generation; stale matches are reported as `EXPIRED`, never guessed. Bidirectional queries,
    frame-correlation retention, strings, and results are bounded with explicit ambiguity,
    truncation, and eviction evidence. The runtime does not inspect UI objects or visual output.
29. Recordings are explicit application-command operations that capture only registered input,
    validated closed action parameters and outcomes, controlled-tick evidence, and completed-frame
    references into an immutable bounded manifest. Protocol/capability versions, optional
    scenario/checkpoint IDs, random seed, and allowlisted scalar configuration are embedded at start.
    Item, tick-span, duration, exact canonical encoded-size, and retention bounds expose stop,
    truncation, incompleteness, and eviction evidence. Paged retrieval remains independently bounded
    by protocol and MCP response limits. Recording creates no input hooks or replay executor and never
    claims replayability unless the application explicitly does so.
30. Determinism comparison repeats one explicitly registered scenario with the same
    application-acknowledged random seed and closed scalar configuration in separate execution
    epochs. The runtime pauses through the registered controller, advances exact controlled ticks,
    and compares only configured completed-frame facts. Results are `EQUAL`, `DIVERGED`, or
    `INCONCLUSIVE`; the first divergence carries both epochs, both frames, the epoch-relative tick,
    and one typed difference. Repeat, tick, entity, property, value, UI-correlation, duration,
    operation-retention, and result-retention bounds expose truncation, eviction, and incomplete
    evidence. Equality is limited to compared observables, not proof of whole-program determinism.

Closing during a frame is rejected. `frame` completes capture and rethrows callback failure.
Disabled runtimes retain no providers or frames and perform no serialization.
