package io.github.teemuki8.libgdx.agent.runtime.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeKind;
import io.github.teemuki8.libgdx.agent.runtime.core.ChangeQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.DecisionQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EventQuery;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameRange;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class PublicJavaWorkflowTest {
    @Test
    void fixtureProducesCompletePublicJavaEvidence() {
        DeterministicSimulation simulation = new DeterministicSimulation();
        AgentRuntime runtime = simulation.startRuntime();
        IntStream.rangeClosed(1, 45).forEach(frame -> simulation.advance(runtime, frame));
        FrameRange range = FrameRange.of(0, 45);

        assertEquals(46, runtime.latestFrame().orElseThrow().frameId().value() + 1);
        assertFalse(runtime.entity(EntityId.of("enemy-2")).isPresent());
        var health = runtime.changes(new ChangeQuery(
                range, Optional.of(EntityId.of("enemy-2")), Optional.empty(),
                Optional.of("health"), 100)).items();
        assertEquals(1, health.size());
        assertEquals(RuntimeValues.integer(100), health.getFirst().before().orElseThrow());
        assertEquals(RuntimeValues.integer(75), health.getFirst().after().orElseThrow());
        assertEquals(ChangeKind.ENTITY_REMOVED,
                runtime.changes(new ChangeQuery(range, Optional.of(EntityId.of("enemy-2")),
                        Optional.empty(), Optional.empty(), 100)).items().getLast().kind());
        assertTrue(runtime.events(new EventQuery(
                range, Optional.of("projectile.hit"), false,
                Optional.of(EntityId.of("enemy-2")), Optional.of(EntityId.of("projectile-3")), 100))
                .items().size() == 1);
        assertEquals(1, runtime.decisions(new DecisionQuery(
                range, Optional.empty(), Optional.of(EntityId.of("tower-1")),
                Optional.of(EntityId.of("enemy-2")), Optional.of("out-of-range"), 100))
                .items().size());
        runtime.close();
    }
}
