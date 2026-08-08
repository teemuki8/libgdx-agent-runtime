# Instrumenting game state

## Static entities and values

```java
EntityRegistration playerRegistration = runtime.entities().register(
        EntityId.of("player-1"),
        EntityType.of("player"),
        player::displayName,
        inspector -> inspector
                .property("health", player::health)
                .property("alive", player::alive)
                .property("position", () -> RuntimeValues.vector2(player.x(), player.y()))
                .property("state", () -> RuntimeValues.enumValue(player.state().name()))
                .property("inventory", () -> RuntimeValues.list(
                        player.items().stream()
                                .map(RuntimeValues::string)
                                .map(RuntimeValue.class::cast)
                                .toList())));
```

Supported values are null, boolean, signed integer, canonical finite decimal, string, enum-like
symbol, two-dimensional vector, list, and ordered object. `NaN` and infinity are rejected.
Collections are copied and deeply immutable.

## Dynamic sources

```java
EntityRegistration enemies = runtime.entities().registerSource(
        "enemies",
        () -> liveEnemies.stream().map(enemy -> InspectableEntity.of(
                EntityId.of(enemy.id()),
                EntityType.of("enemy"),
                enemy::displayName,
                inspector -> inspector
                        .property("health", enemy::health)
                        .property("state", () ->
                                RuntimeValues.enumValue(enemy.state().name())))));
```

Sources and properties are evaluated on the capture thread at frame completion. Entities and
properties are sorted by stable identifiers. Static entities win deterministic duplicate-ID
resolution; later duplicates are omitted with diagnostics.

## Events

```java
Optional<EventId> eventId = runtime.emit(EventSpec.type("damage.applied")
        .subject(EntityId.of("enemy-1"))
        .source(EntityId.of("projectile-3"))
        .attribute("amount", RuntimeValues.integer(25)));
```

Emission assigns the current open frame immediately. Emitting between frames is a typed lifecycle
error. A diff remains `UNKNOWN` unless game code explicitly calls:

```java
runtime.causeNextChange(
        EntityId.of("enemy-1"),
        "health",
        ChangeCause.event(eventId.orElseThrow()));
```

## Removal and failures

Close a registration handle outside a frame on the capture thread. The next frame records
`ENTITY_REMOVED`; historical versions remain until frame eviction.

Removed entities stay queryable while any retained frame holds their immutable snapshot:

```java
EntityHistoryPage page = runtime.entityHistory(
        EntityId.of("enemy-1"), FrameRange.of(1, 60), 0, 25);
// page.current() is empty once removed; page.finalRetainedState() is the bounded final
// pre-removal snapshot; versions page independently via nextVersionOffset/hasMoreVersions.
```

`current` reports newest-frame presence only, `finalRetainedState` is sourced from retained frame
snapshots (never synthesized from the removal change), and version pagination is independent from
change pagination. After the entity's last retained frame is evicted, the query throws
`ENTITY_HISTORY_NOT_RETAINED` instead of inventing state. The existing two-argument
`entityHistory(EntityId, FrameRange)` and the `runtime_entity` protocol/MCP tool keep their frozen
V1 behavior; the protocol-2.0 `runtime_entity_history` command and MCP tool expose the paginated
page.

Provider failures do not escape the render loop. The frame retains provider name, optional entity
and property, and structured failure evidence: a stable category, the exception class, a
deterministic session-prefixed correlation identifier, and optional sanitized detail. Raw
application messages and stack traces are not serialized. An application-owned sanitizer may opt
into bounded public detail that appears only in the structured field and never in the legacy
642-character envelope rendered by protocol 1.x; a throwing sanitizer fails closed. A failed
property is omitted and diagnostics make that omission explicit.

Only registered fields are visible. The runtime never traverses arbitrary object fields.

## Making values visible to the UI harness

Registered entities and properties become visible to the harness only through an explicit
per-frame correlation recorded under a stable token, matched by a harness-side binding, and
compared by the harness `ui_runtime_compare` tool. Without a provable correlation the harness
reports `UNCORRELATED`/`STALE`, never a guess. See
[Frame correlation](frame-correlation.md) for the full contract, the loop-order requirement for
`EQUAL`, and the markup preview reference implementation.
