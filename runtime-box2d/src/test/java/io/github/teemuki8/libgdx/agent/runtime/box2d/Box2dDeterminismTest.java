package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EventType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationConfigurationRequirement;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationEvidenceRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Box2dDeterminismTest {
    @Test
    void compilesExplicitWorldAndSelectedPhysicsEvidenceToGenericCoreSpec() {
        Box2dDeterminism.WorldSettings settings = new Box2dDeterminism.WorldSettings(
                16_666_667, new Box2dVector(0, -9.8), 8, 3,
                true, true, true);
        SimulationDeterminismSpec spec = Box2dDeterminism.builder(
                        "main", settings, "player-move", 7, RuntimeValues.object(), 2, 60)
                .body("player", "position", "linearVelocity", "awake")
                .fixture("player-fixture", "shapeType", "sensor")
                .joint("rope", "jointType", "anchorA", "anchorB")
                .activeContacts()
                .contactEvents()
                .input(1, "move-right", RuntimeValues.object(
                        RuntimeValues.field("pressed", RuntimeValues.bool(true))))
                .build();

        assertEquals(16_666_667, spec.execution().deltaNanos());
        assertEquals(List.of(
                EntityId.of("box2d.body.player"),
                EntityId.of("box2d.contacts.main"),
                EntityId.of("box2d.fixture.player-fixture"),
                EntityId.of("box2d.joint.rope")),
                spec.execution().profile().comparisonScope().entityIds());
        assertEquals(List.of("activeContacts", "anchorA", "anchorB", "awake",
                "complete", "jointType", "linearVelocity", "position", "sensor", "shapeType"),
                spec.execution().profile().comparisonScope().properties());
        assertEquals(List.of(
                EventType.of("box2d.contact.begin"),
                EventType.of("box2d.contact.end"),
                EventType.of("box2d.contact.postSolve"),
                EventType.of("box2d.contact.preSolve")), spec.eventTypes());
        assertEquals(List.of(new SimulationEvidenceRequirement(
                EntityId.of("box2d.contacts.main"), "complete")),
                spec.evidenceRequirements());
        assertEquals(1, spec.inputs().size());

        assertEquals(List.of(
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "continuousPhysics", RuntimeValues.bool(true)),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "fixedStepNanos", RuntimeValues.integer(16_666_667)),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "gravity", new RuntimeValue.Vector2Value(
                                RuntimeValues.decimal("0"), RuntimeValues.decimal("-9.8"))),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "positionIterations", RuntimeValues.integer(3)),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "sleepingAllowed", RuntimeValues.bool(true)),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "velocityIterations", RuntimeValues.integer(8)),
                new SimulationConfigurationRequirement(EntityId.of("box2d.world.main"),
                        "warmStarting", RuntimeValues.bool(true))),
                spec.configurationRequirements());
    }

    @Test
    void contactModesAndBuilderInputsRemainExplicitAndBounded() {
        Box2dDeterminism.Builder builder = Box2dDeterminism.builder(
                "main", settings(), "ball-drop", 1, RuntimeValues.object(), 2, 10);
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalArgumentException.class,
                () -> builder.body("ball"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.body(" ", "position"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.input(11, "move", RuntimeValues.object()));
        assertThrows(IllegalArgumentException.class, () -> new Box2dDeterminism.WorldSettings(
                1, new Box2dVector(0, 0), 0, 3, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new Box2dDeterminism.WorldSettings(
                1, new Box2dVector(Double.MAX_VALUE, 0), 8, 3, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new Box2dDeterminism.WorldSettings(
                1, new Box2dVector(Double.MIN_VALUE, 0), 8, 3, true, true, true));

        SimulationDeterminismSpec contacts = Box2dDeterminism.builder(
                        "main", settings(), "ball-drop", 1,
                        RuntimeValues.object(), 2, 10)
                .activeContacts().build();
        assertEquals(false, contacts.execution().profile().comparisonScope().includeEvents());
        assertEquals(List.of(), contacts.eventTypes());

        SimulationDeterminismSpec events = Box2dDeterminism.builder(
                        "main", settings(), "ball-drop", 1,
                        RuntimeValues.object(), 2, 10)
                .contactEvents().build();
        assertEquals(List.of(EntityId.of("box2d.contacts.main")),
                events.execution().profile().comparisonScope().entityIds());
        assertEquals(List.of("complete"),
                events.execution().profile().comparisonScope().properties());
    }

    @Test
    void builderRejectsBeforeRetainingPartialOrOversizedSelections() {
        Box2dDeterminism.Builder atomic = Box2dDeterminism.builder(
                "main", settings(), "ball-drop", 1, RuntimeValues.object(), 2, 10);
        assertThrows(NullPointerException.class,
                () -> atomic.body("ball", "position", null));
        SimulationDeterminismSpec retained = atomic.body("ball", "awake").build();
        assertEquals(List.of("awake"),
                retained.execution().profile().comparisonScope().properties());

        String[] tooManyProperties = new String[101];
        java.util.Arrays.fill(tooManyProperties, "position");
        Box2dDeterminism.Builder selections = Box2dDeterminism.builder(
                "main", settings(), "ball-drop", 1, RuntimeValues.object(), 2, 10);
        assertThrows(IllegalArgumentException.class,
                () -> selections.body("ball", tooManyProperties));

        Box2dDeterminism.Builder inputs = Box2dDeterminism.builder(
                "main", settings(), "ball-drop", 1, RuntimeValues.object(), 2, 10)
                .body("ball", "position");
        for (int index = 0; index < SimulationDeterminismSpec.MAX_INPUTS; index++) {
            inputs.input(1, "move", RuntimeValues.object());
        }
        assertThrows(IllegalArgumentException.class,
                () -> inputs.input(1, "move", RuntimeValues.object()));

        Box2dDeterminism.Builder contactAtomic = Box2dDeterminism.builder(
                "main", settings(), "ball-drop", 1, RuntimeValues.object(), 2, 10);
        for (int index = 0; index < 100; index++) {
            contactAtomic.body("body-" + index, "position");
        }
        assertThrows(IllegalArgumentException.class, contactAtomic::contactEvents);
        SimulationDeterminismSpec withoutPartialContact = contactAtomic.build();
        assertEquals(false,
                withoutPartialContact.execution().profile().comparisonScope().includeEvents());
        assertEquals(List.of(), withoutPartialContact.eventTypes());
    }

    private static Box2dDeterminism.WorldSettings settings() {
        return new Box2dDeterminism.WorldSettings(
                16, new Box2dVector(0, -10), 8, 3, true, true, true);
    }
}
