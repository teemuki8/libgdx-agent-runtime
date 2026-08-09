package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

final class Box2dConfigurationTest {
    @Test
    void unitTransformConvertsFiniteScalarsAndImmutableVectorsBothWays() {
        Box2dUnitTransform transform = new Box2dUnitTransform(100.0);

        assertEquals(250.0, transform.physicsToRender(2.5));
        assertEquals(2.5, transform.renderToPhysics(250.0));
        assertEquals(new Box2dVector(200.0, -50.0),
                transform.physicsToRender(new Box2dVector(2.0, -0.5)));
        assertEquals(new Box2dVector(2.0, -0.5),
                transform.renderToPhysics(new Box2dVector(200.0, -50.0)));

        assertThrows(IllegalArgumentException.class, () -> new Box2dUnitTransform(0));
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dUnitTransform(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> transform.physicsToRender(Double.MAX_VALUE));
    }

    @Test
    void limitsAndWorldSpecRejectInvalidOrUnboundedConfiguration() {
        Box2dAdapterLimits defaults = Box2dAdapterLimits.developmentDefaults();
        assertEquals(16, defaults.worlds());
        assertEquals(64, defaults.shapeVertices());
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dAdapterLimits(0, 1, 1, 1, 1, 32, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dAdapterLimits(1, 1, 1, 1, 1_000_001, 32, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dAdapterLimits(1, 1, 1, 1, 1, 19, 1));

        Box2dWorldSpec spec = new Box2dWorldSpec(true, true, true, 6, 2,
                OptionalDouble.of(60.0), new Box2dUnitTransform(100));
        assertEquals(6, spec.velocityIterations());
        assertThrows(IllegalArgumentException.class, () -> new Box2dWorldSpec(
                true, true, true, 0, 2, OptionalDouble.empty(),
                new Box2dUnitTransform(100)));
        assertThrows(IllegalArgumentException.class, () -> new Box2dWorldSpec(
                true, true, true, 6, 2, OptionalDouble.of(Double.POSITIVE_INFINITY),
                new Box2dUnitTransform(100)));
        assertThrows(IllegalArgumentException.class, () -> new Box2dWorldSpec(
                true, true, true, 6, 2, OptionalDouble.of(Double.MAX_VALUE),
                new Box2dUnitTransform(100)));
        assertThrows(IllegalArgumentException.class, () -> new Box2dWorldSpec(
                true, true, true, 6, 2, OptionalDouble.of(Double.MIN_VALUE),
                new Box2dUnitTransform(100)));
    }
}
