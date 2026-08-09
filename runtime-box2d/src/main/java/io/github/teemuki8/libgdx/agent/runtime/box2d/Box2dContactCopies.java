package io.github.teemuki8.libgdx.agent.runtime.box2d;

import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.badlogic.gdx.physics.box2d.WorldManifold;
import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Immediate primitive copies of native-backed callback values. */
final class Box2dContactCopies {
    private Box2dContactCopies() {}

    static CurrentManifold current(Contact contact, boolean reversed, int pointLimit) {
        WorldManifold manifold = contact.getWorldManifold();
        int observed = manifold.getNumberOfContactPoints();
        int retained = Math.min(observed, pointLimit);
        ArrayList<Box2dVector> points = new ArrayList<>(retained);
        for (int index = 0; index < retained; index++) {
            points.add(vector(manifold.getPoints()[index]));
        }
        Box2dVector normal = vector(manifold.getNormal());
        if (reversed) {
            normal = new Box2dVector(-normal.x(), -normal.y());
        }
        List<Truncation> truncations = observed > retained
                ? List.of(new Truncation("box2d.contact.points", observed, retained, pointLimit))
                : List.of();
        return new CurrentManifold(points, Optional.of(normal), truncations);
    }

    static ImpulseCopy impulses(ContactImpulse impulse, boolean reversed, int limit) {
        int observed = impulse.getCount();
        int retained = Math.min(observed, limit);
        float[] nativeNormal = impulse.getNormalImpulses();
        float[] nativeTangent = impulse.getTangentImpulses();
        ArrayList<Box2dContactRecord.Impulse> values = new ArrayList<>(retained);
        for (int index = 0; index < retained; index++) {
            double tangent = nativeTangent[index];
            values.add(new Box2dContactRecord.Impulse(nativeNormal[index],
                    reversed ? -tangent : tangent));
        }
        List<Truncation> truncations = observed > retained
                ? List.of(new Truncation("box2d.contact.impulses", observed, retained, limit))
                : List.of();
        return new ImpulseCopy(values, truncations);
    }

    static OldCopy oldManifold(Manifold manifold, boolean reversed, int limit) {
        int observed = manifold.getPointCount();
        int retained = Math.min(observed, limit);
        Manifold.ManifoldPoint[] nativePoints = manifold.getPoints();
        ArrayList<Box2dContactRecord.OldManifoldPoint> points = new ArrayList<>(retained);
        for (int index = 0; index < retained; index++) {
            Manifold.ManifoldPoint point = nativePoints[index];
            points.add(new Box2dContactRecord.OldManifoldPoint(
                    Integer.toUnsignedLong(point.contactID), point.normalImpulse,
                    reversed ? -point.tangentImpulse : point.tangentImpulse));
        }
        Box2dContactRecord.ManifoldType type = switch (manifold.getType()) {
            case Circle -> Box2dContactRecord.ManifoldType.CIRCLES;
            case FaceA -> reversed ? Box2dContactRecord.ManifoldType.FACE_B
                    : Box2dContactRecord.ManifoldType.FACE_A;
            case FaceB -> reversed ? Box2dContactRecord.ManifoldType.FACE_A
                    : Box2dContactRecord.ManifoldType.FACE_B;
        };
        List<Truncation> truncations = observed > retained
                ? List.of(new Truncation(
                        "box2d.contact.oldManifoldPoints", observed, retained, limit))
                : List.of();
        return new OldCopy(new Box2dContactRecord.OldManifold(type, points), truncations);
    }

    private static Box2dVector vector(com.badlogic.gdx.math.Vector2 value) {
        return new Box2dVector(value.x, value.y);
    }

    record CurrentManifold(List<Box2dVector> points, Optional<Box2dVector> normal,
            List<Truncation> truncations) {
        CurrentManifold {
            points = List.copyOf(points);
            normal = Optional.of(normal.orElseThrow());
            truncations = List.copyOf(truncations);
        }
    }

    record ImpulseCopy(List<Box2dContactRecord.Impulse> values, List<Truncation> truncations) {
        ImpulseCopy {
            values = List.copyOf(values);
            truncations = List.copyOf(truncations);
        }
    }

    record OldCopy(Box2dContactRecord.OldManifold value, List<Truncation> truncations) {
        OldCopy {
            truncations = List.copyOf(truncations);
        }
    }
}
