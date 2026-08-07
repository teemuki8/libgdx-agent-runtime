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

Provider failures do not escape the render loop. The frame retains provider name, optional entity
and property, exception class, and bounded message. Stack traces are not serialized. A failed
property is omitted and diagnostics make that omission explicit.

Only registered fields are visible. The runtime never traverses arbitrary object fields.

## Making values visible to the UI harness

Registered entities and properties become visible to the harness only through an explicit
per-frame correlation recorded under a stable token, matched by a harness-side binding, and
compared by the harness `ui_runtime_compare` tool. Without a provable correlation the harness
reports `UNCORRELATED`/`STALE`, never a guess. See
[Frame correlation](frame-correlation.md) for the full contract, the loop-order requirement for
`EQUAL`, and the markup preview reference implementation.
