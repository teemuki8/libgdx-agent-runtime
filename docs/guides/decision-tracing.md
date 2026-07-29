# Decision tracing

Decision scopes record game-supplied semantic facts; they do not reconstruct AI reasoning.

```java
try (DecisionScope decision = runtime.beginDecision(
        DecisionType.of("target-selection"),
        EntityId.of("tower-12"))) {
    decision.reject(
            EntityId.of("enemy-4"),
            Reason.of("out-of-range"),
            RuntimeValues.field("distance", RuntimeValues.decimal(14.2)),
            RuntimeValues.field("range", RuntimeValues.decimal(10.0)));
    decision.accept(
            EntityId.of("enemy-7"),
            RuntimeValues.field("priority", RuntimeValues.integer(4)),
            RuntimeValues.field("distance", RuntimeValues.decimal(8.1)));
    decision.choose(
            EntityId.of("enemy-7"),
            Reason.of("highest-priority-in-range"));
}
```

Reasons have stable codes and optional bounded descriptions. Candidate attributes use the same
closed value model as properties and events.

V1 rejects nested decisions. A scope still open at frame completion is `ABORTED`. If the enclosing
`runtime.frame` callback fails, every decision in that frame is retained as `ABORTED`, even if
try-with-resources invoked `close` while unwinding. Closing normally yields `COMPLETED`. No choice
is required; a completed trace may have no selection.

To correlate an observed change explicitly:

```java
runtime.causeNextChange(entityId, "target", ChangeCause.decision(
        decision.id().orElseThrow()));
```
