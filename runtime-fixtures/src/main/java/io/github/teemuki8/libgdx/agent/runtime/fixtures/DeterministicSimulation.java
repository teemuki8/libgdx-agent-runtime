package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointHandle;
import io.github.teemuki8.libgdx.agent.runtime.core.CheckpointProvider;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionScope;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionType;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.EventSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.InspectableEntity;
import io.github.teemuki8.libgdx.agent.runtime.core.InputSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.Reason;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.libgdx.LibGdxAgentRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tiny deterministic tower simulation used by every public vertical-stack fixture test. */
public final class DeterministicSimulation {
    /** Stable fixture session. */
    public static final SessionId SESSION_ID = SessionId.of("deterministic-fixture");
    private final Unit player = new Unit("player-1", "player", 100, 0, 0, "READY");
    private final Unit tower1 = new Unit("tower-1", "tower", 100, 0, 0, "IDLE");
    private final Unit tower2 = new Unit("tower-2", "tower", 100, 30, 0, "IDLE");
    private final List<Unit> enemies = new ArrayList<>();
    private boolean paused;
    private int nextControlledFrame = 1;

    /** Creates initial fixture state. */
    public DeterministicSimulation() {
        enemies.add(new Unit("enemy-1", "enemy", 100, 20, 5, "MOVING"));
        enemies.add(new Unit("enemy-2", "enemy", 100, 8, 1, "MOVING"));
    }

    /** Creates, instruments, and starts a runtime on the calling thread. */
    public AgentRuntime startRuntime() {
        return startRuntime(null);
    }

    /** Creates and starts a runtime with an explicit application command bridge. */
    public AgentRuntime startRuntime(ApplicationCommandDispatcher dispatcher) {
        LibGdxAgentRuntime.Builder builder = LibGdxAgentRuntime.builder()
                .captureThread(Thread.currentThread())
                .sessionId(SESSION_ID);
        if (dispatcher != null) {
            builder.commandDispatcher(dispatcher);
        }
        AgentRuntime runtime = builder.build();
        register(runtime, player);
        register(runtime, tower1);
        register(runtime, tower2);
        runtime.entities().registerSource("enemies", () -> enemies.stream().map(this::inspectable));
        runtime.uiCorrelations().register(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiBinding(
                        "player-state-hud", EntityId.of("player-1"), Optional.of("state"),
                        "fixture-hud", "player-state",
                        new io.github.teemuki8.libgdx.agent.runtime.core.UiBindingValidity(
                                Optional.empty(), Optional.empty(), Optional.empty())));
        runtime.inputs().register(InputSpec.builder("set-player-state")
                .description("Sets the deterministic player state on a controlled tick")
                .requiredString("state")
                .handler(parameters -> player.state = parameters.requiredString("state"))
                .build());
        if (dispatcher != null) {
            runtime.scenarios().register(
                    new io.github.teemuki8.libgdx.agent.runtime.core.ScenarioDescriptor(
                            "deterministic-fixture", Optional.of(
                                    "Restores the seeded deterministic fixture state")),
                    context -> {
                        context.randomSeed().orElseThrow();
                        reset();
                    });
            runtime.checkpoints().register(new CheckpointProvider() {
                @Override public CheckpointHandle create() {
                    return new FixtureCheckpoint(player.state, tower1.state);
                }
                @Override public void restore(CheckpointHandle handle) {
                    FixtureCheckpoint checkpoint = (FixtureCheckpoint) handle;
                    player.state = checkpoint.playerState();
                    tower1.state = checkpoint.towerState();
                }
                @Override public void dispose(CheckpointHandle handle) {}
            });
            runtime.controls().register(SimulationControllerSpec.builder()
                    .pause(() -> paused = true)
                    .resume(() -> paused = false)
                    .tick(deltaNanos -> advanceControlled(runtime))
                    .condition("frame-48-complete", "Fixture frame 48 has completed",
                            () -> nextControlledFrame > 48)
                    .build());
        }
        runtime.start();
        runtime.uiCorrelations().recordFrame(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation(
                        runtime.currentEpoch(),
                        runtime.latestFrame().orElseThrow().frameId(),
                        "fixture-hud", Optional.of("ui-baseline"), Optional.of("fixture-baseline")));
        return runtime;
    }

    /** Advances one exact fixture frame through public runtime APIs. */
    public void advance(AgentRuntime runtime, int frame) {
        if (!paused) {
            nextControlledFrame = Math.max(nextControlledFrame, frame + 1);
            runtime.frame(16_000_000, () -> update(runtime, frame));
        }
    }

    private void advanceControlled(AgentRuntime runtime) {
        update(runtime, nextControlledFrame++);
    }

    private void update(AgentRuntime runtime, int frame) {
        if (frame == 10) {
            Unit spawned = new Unit("enemy-3", "enemy", 100, 35, 2, "MOVING");
            enemies.add(spawned);
            runtime.emit(EventSpec.type("entity.spawned").subject(EntityId.of(spawned.id)));
        }
        if (frame == 20) {
            try (DecisionScope decision = runtime.beginDecision(
                    DecisionType.of("target-selection"), EntityId.of(tower1.id))) {
                decision.reject(EntityId.of("enemy-1"), Reason.of("out-of-range"),
                        RuntimeValues.field("distance", RuntimeValues.decimal(20.6)),
                        RuntimeValues.field("range", RuntimeValues.decimal(10)));
                decision.accept(EntityId.of("enemy-2"),
                        RuntimeValues.field("distance", RuntimeValues.decimal(8.1)));
                decision.choose(EntityId.of("enemy-2"), Reason.of("nearest-in-range"));
            }
            tower1.state = "TRACKING";
        }
        if (frame == 25) {
            Unit enemy = enemy("enemy-2");
            runtime.emit(EventSpec.type("projectile.hit")
                    .subject(EntityId.of(enemy.id))
                    .source(EntityId.of("projectile-3"))
                    .attribute("amount", RuntimeValues.integer(25)));
            enemy.health -= 25;
        }
        if (frame == 39) {
            enemy("enemy-2").state = "DEAD";
        }
        if (frame == 40) {
            runtime.emit(EventSpec.type("entity.destroyed")
                    .subject(EntityId.of("enemy-2")));
            enemies.removeIf(enemy -> enemy.id.equals("enemy-2"));
        }
    }

    private void reset() {
        player.health = 100;
        player.state = "READY";
        tower1.health = 100;
        tower1.state = "IDLE";
        tower2.health = 100;
        tower2.state = "IDLE";
        enemies.clear();
        enemies.add(new Unit("enemy-1", "enemy", 100, 20, 5, "MOVING"));
        enemies.add(new Unit("enemy-2", "enemy", 100, 8, 1, "MOVING"));
        paused = true;
        nextControlledFrame = 1;
    }

    private void register(AgentRuntime runtime, Unit unit) {
        runtime.entities().register(EntityId.of(unit.id), EntityType.of(unit.type),
                () -> unit.id, inspector -> inspect(inspector, unit));
    }

    private InspectableEntity inspectable(Unit unit) {
        return InspectableEntity.of(EntityId.of(unit.id), EntityType.of(unit.type),
                () -> unit.id, inspector -> inspect(inspector, unit));
    }

    private static void inspect(
            io.github.teemuki8.libgdx.agent.runtime.core.EntityInspector inspector, Unit unit) {
        inspector.property("health", () -> unit.health)
                .property("position", () -> RuntimeValues.vector2(unit.x, unit.y))
                .property("state", () -> RuntimeValues.enumValue(unit.state));
    }

    private Unit enemy(String id) {
        return enemies.stream().filter(enemy -> enemy.id.equals(id)).findFirst().orElseThrow();
    }

    private record FixtureCheckpoint(
            String playerState, String towerState) implements CheckpointHandle {}

    private static final class Unit {
        private final String id;
        private final String type;
        private long health;
        private final double x;
        private final double y;
        private String state;

        Unit(String id, String type, long health, double x, double y, String state) {
            this.id = id;
            this.type = type;
            this.health = health;
            this.x = x;
            this.y = y;
            this.state = state;
        }
    }
}
