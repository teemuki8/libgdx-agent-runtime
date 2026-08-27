package io.github.teemuki8.libgdx.agent.runtime.box2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
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

        Box2dWorldSpec spec = new Box2dWorldSpec(4, new Box2dUnitTransform(100));
        assertEquals(4, spec.subStepCount());
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dWorldSpec(0, new Box2dUnitTransform(100)));
        assertThrows(IllegalArgumentException.class,
                () -> new Box2dWorldSpec(17, new Box2dUnitTransform(100)));
    }

    @Test
    void publishedModuleContainsOnlyTheOfficialBox2d3Backend() throws IOException {
        String source;
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            source = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .reduce("", String::concat);
        }
        assertFalse(source.contains("com.badlogic.gdx.physics.box2d"));
        assertTrue(source.contains("com.badlogic.gdx.box2d"));

        String lock = Files.readString(Path.of("gradle.lockfile"));
        assertTrue(lock.contains("gdx-box2d:3.1.1-0"));
        assertFalse(lock.contains("gdx-box2d:1.14.2"));
        assertFalse(lock.contains("gdx-box2d-platform:1.14.2"));
    }
}
