package io.github.teemuki8.libgdx.agent.runtime.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.teemuki8.libgdx.agent.runtime.core.AssertionStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismStatus;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeterministicBox2dExampleTest {
    @BeforeAll
    static void initializeNativeBox2d() {
        GdxNativesLoader.load();
        Box2D.init();
    }

    @Test
    void actualNativeWorkflowProducesInspectableDeterministicEvidence() {
        try (DeterministicBox2dExample example =
                DeterministicBox2dExample.create(Runnable::run)) {
            DeterministicBox2dExample.Box2dResult result = example.runWorkflow();

            assertEquals(AssertionStatus.PASS, result.positionStatus());
            assertEquals(AssertionStatus.PASS, result.contactStatus());
            assertEquals(DeterminismStatus.EQUAL, result.determinismStatus());
            assertTrue(result.tickFrameCorrelated());
            assertFalse(result.wholeProgramDeterminismClaimed());

            assertTrue(example.runtime().entity(EntityId.of("box2d.world.main")).isPresent());
            assertTrue(example.runtime().entity(EntityId.of("box2d.body.player")).isPresent());
            assertTrue(example.runtime().entity(EntityId.of("box2d.fixture.player-shape"))
                    .isPresent());
            assertTrue(example.runtime().entity(EntityId.of("box2d.joint.static-link"))
                    .isPresent());
            assertTrue(example.runtime().entity(EntityId.of("box2d.contacts.main"))
                    .isPresent());
        }
    }
}
