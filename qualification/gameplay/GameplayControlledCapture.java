import io.github.teemuki8.libgdx.agent.gameplay.core.GameplayLimits;
import io.github.teemuki8.libgdx.agent.gameplay.core.command.CommandEnvelope;
import io.github.teemuki8.libgdx.agent.gameplay.core.command.InteractCommand;
import io.github.teemuki8.libgdx.agent.gameplay.core.component.Health;
import io.github.teemuki8.libgdx.agent.gameplay.core.component.StandardComponents;
import io.github.teemuki8.libgdx.agent.gameplay.core.system.GameSystem;
import io.github.teemuki8.libgdx.agent.gameplay.core.system.SystemContext;
import io.github.teemuki8.libgdx.agent.gameplay.core.system.SystemDescriptor;
import io.github.teemuki8.libgdx.agent.gameplay.core.system.SystemPhase;
import io.github.teemuki8.libgdx.agent.gameplay.core.value.CommandSourceId;
import io.github.teemuki8.libgdx.agent.gameplay.core.value.EntityId;
import io.github.teemuki8.libgdx.agent.gameplay.core.value.SystemId;
import io.github.teemuki8.libgdx.agent.gameplay.core.visual.WorldVisualSnapshot;
import io.github.teemuki8.libgdx.agent.gameplay.core.world.EntityDraft;
import io.github.teemuki8.libgdx.agent.gameplay.core.world.GameWorld;
import io.github.teemuki8.libgdx.agent.gameplay.runtime.GameplayRuntimeBridge;
import io.github.teemuki8.libgdx.agent.gameplay.runtime.StandardRuntimeProjections;
import io.github.teemuki8.libgdx.agent.runtime.core.*;
import java.time.Duration;
import java.util.List;

/** Explicitly compiled external consumer; never a runtime production dependency. */
public final class GameplayControlledCapture {
    private static final long STEP = 16_666_667L;
    private static final EntityId PLAYER = EntityId.of("player");

    public static void main(String[] args) {
        try (AgentRuntime runtime = AgentRuntime.builder()
                .commandDispatcher(Runnable::run).build();
                GameplayRuntimeBridge bridge = new GameplayRuntimeBridge(runtime,
                        StandardRuntimeProjections.registry(), GameplayLimits.defaults())) {
            GameWorld.Builder builder = GameWorld.builder(
                            GameplayLimits.defaults(), StandardComponents.registry())
                    .fixedStepNanos(STEP)
                    .initializer(sink -> sink.spawn(EntityDraft.builder(PLAYER)
                            .with(Health.TYPE, new Health(3, 3)).build()));
            bridge.systems().forEach(builder::system);
            builder.system(new GameSystem() {
                public SystemDescriptor descriptor() {
                    return new SystemDescriptor(SystemId.of("interact"), SystemPhase.GAMEPLAY, 1);
                }

                public void update(SystemContext context) {
                    for (CommandEnvelope envelope : context.commands()) {
                        if (envelope.command() instanceof InteractCommand) {
                            context.replace(PLAYER, Health.TYPE, new Health(2, 3));
                        }
                    }
                }
            });
            builder.system(new GameSystem() {
                public SystemDescriptor descriptor() {
                    return new SystemDescriptor(SystemId.of("visuals"), SystemPhase.RENDER_PREP, 1);
                }

                public void update(SystemContext context) {
                    bridge.prepareVisuals(new WorldVisualSnapshot(context.tick(), List.of()));
                }
            });
            try (GameWorld world = builder.build()) {
                long[] sequence = {0};
                runtime.simulation().register(SimulationTimelineSpec.fixedStep(STEP),
                        SimulationFrameOwnership.CALLBACK);
                runtime.controls().register(SimulationControllerSpec.builder()
                        .pause(() -> {}).resume(() -> {}).acknowledgedTick(delta -> {
                            world.step();
                            return delta;
                        }).build());
                runtime.inputs().register(InputSpec.builder("interact").handler(parameters ->
                        world.enqueue(new CommandEnvelope(world.tick(),
                                CommandSourceId.of("production"), sequence[0]++,
                                new InteractCommand(PLAYER, PLAYER)))).build());
                runtime.start();
                runtime.controls().control(true, "pause", Duration.ofSeconds(1));
                InputTimelineResult result = runtime.inputs().executeTimeline(
                        new InputTimelineSpec(1, List.of(new InputTimelineTransition(
                                "interact-once", 1, "interact", RuntimeValues.object()))),
                        "interact", Duration.ofSeconds(1)).result().orElseThrow();
                require(result.stopReason() == InputTimelineStopReason.COMPLETED, result.toString());
                require(world.tick() == 1, "exactly one world tick");
                require(runtime.latestFrame().orElseThrow().frameId().equals(new FrameId(1)),
                        "exactly one runtime frame");
                RuntimeValue health = runtime.latestFrame().orElseThrow().entity(
                        io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of("gameplay.entity.player"))
                        .orElseThrow().property("health.current").orElseThrow();
                require(health.equals(RuntimeValues.integer(2)), "input applied on requested tick");
                require(runtime.simulation().ticks(new SimulationTickQuery(
                        new ExecutionEpochId(0), 1, 1, 1)).ticks().getFirst().outcome()
                        == SimulationTickOutcome.COMPLETED, "correlated completed tick");
                System.out.println("PASS gameplay input -> world tick 0 -> runtime frame 1, health=2");
            }
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
    }
}
