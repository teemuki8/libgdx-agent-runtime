# V1 behavioral contract

This document makes lifecycle and evidence edge cases explicit.

1. An event receives the currently open frame ID synchronously in `emit`.
2. Events between frames are rejected with `INVALID_LIFECYCLE`; there is no hidden queue.
3. `start()` captures baseline frame 0 and produces no property-added changes.
4. A disappeared entity produces one `ENTITY_REMOVED` change; its prior snapshots remain in
   history until frame eviction.
5. Static IDs are unique at registration. Across dynamic sources, static wins, then source name
   order; later duplicates are omitted with a diagnostic.
6. Property failures omit that property and retain provider, entity, property, exception class, and
   bounded message without a stack trace.
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

Closing during a frame is rejected. `frame` completes capture and rethrows callback failure.
Disabled runtimes retain no providers or frames and perform no serialization.
