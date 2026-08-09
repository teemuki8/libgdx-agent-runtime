package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismProfile;
import io.github.teemuki8.libgdx.agent.runtime.core.DeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EventType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationConfigurationRequirement;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismInput;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationDeterminismSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationEvidenceRequirement;
import io.github.teemuki8.libgdx.agent.runtime.core.SnapshotComparisonScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Data-only builder for exact determinism over explicitly registered Box2D evidence. */
public final class Box2dDeterminism {
    private static final List<EventType> CONTACT_EVENTS = List.of(
            EventType.of("box2d.contact.begin"), EventType.of("box2d.contact.end"),
            EventType.of("box2d.contact.postSolve"), EventType.of("box2d.contact.preSolve"));

    private Box2dDeterminism() {}

    /** Explicit world configuration expected in every reset baseline. */
    public record WorldSettings(long fixedStepNanos, Box2dVector gravity,
            int velocityIterations, int positionIterations, boolean sleepingAllowed,
            boolean warmStarting, boolean continuousPhysics) {
        /** Validates fixed-step, solver, and float-representable gravity testimony. */
        public WorldSettings {
            if (fixedStepNanos <= 0 || velocityIterations <= 0 || velocityIterations > 1_000
                    || positionIterations <= 0 || positionIterations > 1_000) {
                throw new IllegalArgumentException("Box2D determinism settings are outside range");
            }
            Objects.requireNonNull(gravity, "gravity");
            requireFiniteFloat(gravity.x());
            requireFiniteFloat(gravity.y());
        }
    }

    /** Starts one explicit Box2D simulation determinism specification. */
    public static Builder builder(String worldId, WorldSettings settings,
            String scenarioId, long randomSeed, RuntimeValue.ObjectValue configuration,
            int repeatCount, int ticksPerRepeat) {
        return new Builder(worldId, settings, scenarioId, randomSeed,
                configuration, repeatCount, ticksPerRepeat);
    }

    /** Mutable construction helper that emits one immutable generic core specification. */
    public static final class Builder {
        private final String worldId;
        private final WorldSettings settings;
        private final String scenarioId;
        private final long randomSeed;
        private final RuntimeValue.ObjectValue configuration;
        private final int repeatCount;
        private final int ticksPerRepeat;
        private final LinkedHashSet<EntityId> entities = new LinkedHashSet<>();
        private final LinkedHashSet<String> properties = new LinkedHashSet<>();
        private final ArrayList<SimulationDeterminismInput> inputs = new ArrayList<>();
        private boolean activeContacts;
        private boolean contactEvents;
        private boolean observableSelected;

        private Builder(String worldId, WorldSettings settings, String scenarioId,
                long randomSeed, RuntimeValue.ObjectValue configuration,
                int repeatCount, int ticksPerRepeat) {
            this.worldId = validateId(worldId, "worldId");
            this.settings = Objects.requireNonNull(settings, "settings");
            this.scenarioId = Objects.requireNonNull(scenarioId, "scenarioId");
            this.randomSeed = randomSeed;
            this.configuration = Objects.requireNonNull(configuration, "configuration");
            this.repeatCount = repeatCount;
            this.ticksPerRepeat = ticksPerRepeat;
            entities.add(entity("world", worldId));
        }

        /** Selects one registered body and one or more exact top-level properties. */
        public Builder body(String bodyId, String... selectedProperties) {
            return select("body", bodyId, selectedProperties);
        }

        /** Selects one registered fixture and one or more exact top-level properties. */
        public Builder fixture(String fixtureId, String... selectedProperties) {
            return select("fixture", fixtureId, selectedProperties);
        }

        /** Selects one registered joint and one or more exact top-level properties. */
        public Builder joint(String jointId, String... selectedProperties) {
            return select("joint", jointId, selectedProperties);
        }

        /** Compares bounded active-contact snapshots and requires complete contact testimony. */
        public Builder activeContacts() {
            activeContacts = true;
            observableSelected = true;
            entities.add(entity("contacts", worldId));
            properties.add("activeContacts");
            return this;
        }

        /** Compares the four selected Box2D contact callback event types. */
        public Builder contactEvents() {
            contactEvents = true;
            observableSelected = true;
            entities.add(entity("contacts", worldId));
            return this;
        }

        /** Adds one registered input repeated before the selected epoch tick. */
        public Builder input(long epochTick, String inputId,
                RuntimeValue.ObjectValue parameters) {
            if (epochTick > ticksPerRepeat) {
                throw new IllegalArgumentException("determinism input tick exceeds the run");
            }
            inputs.add(new SimulationDeterminismInput(epochTick, inputId, parameters));
            return this;
        }

        /** Builds the immutable JDK-only simulation determinism request. */
        public SimulationDeterminismSpec build() {
            if (!observableSelected) {
                throw new IllegalStateException("at least one Box2D observable must be selected");
            }
            SnapshotComparisonScope scope = new SnapshotComparisonScope(
                    List.copyOf(entities), List.copyOf(properties), List.of(),
                    contactEvents, false);
            DeterminismSpec execution = new DeterminismSpec(
                    scenarioId, randomSeed, configuration, repeatCount, ticksPerRepeat,
                    settings.fixedStepNanos(), new DeterminismProfile(scope, false));
            List<SimulationEvidenceRequirement> evidence = activeContacts || contactEvents
                    ? List.of(new SimulationEvidenceRequirement(
                            entity("contacts", worldId), "complete")) : List.of();
            return new SimulationDeterminismSpec(execution, inputs,
                    configurationRequirements(), evidence,
                    contactEvents ? CONTACT_EVENTS : List.of());
        }

        private Builder select(String kind, String id, String... selectedProperties) {
            String stableId = validateId(id, kind + "Id");
            Objects.requireNonNull(selectedProperties, "selectedProperties");
            if (selectedProperties.length == 0) {
                throw new IllegalArgumentException(
                        "at least one Box2D property must be selected");
            }
            entities.add(entity(kind, stableId));
            for (String property : selectedProperties) {
                properties.add(validateProperty(property));
            }
            observableSelected = true;
            return this;
        }

        private List<SimulationConfigurationRequirement> configurationRequirements() {
            EntityId world = entity("world", worldId);
            return List.of(
                    new SimulationConfigurationRequirement(world, "fixedStepNanos",
                            RuntimeValues.integer(settings.fixedStepNanos())),
                    new SimulationConfigurationRequirement(world, "gravity",
                            new RuntimeValue.Vector2Value(floatDecimal(settings.gravity().x()),
                                    floatDecimal(settings.gravity().y()))),
                    new SimulationConfigurationRequirement(world, "velocityIterations",
                            RuntimeValues.integer(settings.velocityIterations())),
                    new SimulationConfigurationRequirement(world, "positionIterations",
                            RuntimeValues.integer(settings.positionIterations())),
                    new SimulationConfigurationRequirement(world, "sleepingAllowed",
                            RuntimeValues.bool(settings.sleepingAllowed())),
                    new SimulationConfigurationRequirement(world, "warmStarting",
                            RuntimeValues.bool(settings.warmStarting())),
                    new SimulationConfigurationRequirement(world, "continuousPhysics",
                            RuntimeValues.bool(settings.continuousPhysics())));
        }
    }

    private static EntityId entity(String kind, String id) {
        return EntityId.of("box2d." + kind + '.' + id);
    }

    private static String validateId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 220) {
            throw new IllegalArgumentException(name + " is outside range");
        }
        return value;
    }

    private static String validateProperty(String value) {
        Objects.requireNonNull(value, "property");
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("property is outside range");
        }
        return value;
    }

    private static RuntimeValue.DecimalValue floatDecimal(double value) {
        requireFiniteFloat(value);
        return new RuntimeValue.DecimalValue(new BigDecimal(Float.toString((float) value)));
    }

    private static void requireFiniteFloat(double value) {
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed) || value != 0 && narrowed == 0) {
            throw new IllegalArgumentException("Box2D setting must be float-representable");
        }
    }
}
