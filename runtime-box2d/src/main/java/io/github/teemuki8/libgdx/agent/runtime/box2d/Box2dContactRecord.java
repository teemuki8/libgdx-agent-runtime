package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immediately copied and canonically oriented Box2D contact callback. */
public record Box2dContactRecord(Phase phase, Key key, Endpoint endpointA, Endpoint endpointB,
        boolean touching, boolean enabled, Availability availability, List<Box2dVector> points,
        Optional<Box2dVector> normal, List<Impulse> impulses,
        Optional<OldManifold> oldManifold, long occurrence, List<Truncation> truncations)
        implements Comparable<Box2dContactRecord> {
    /** Validates closed phase availability and defensively copies bounded values. */
    public Box2dContactRecord {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(endpointA, "endpointA");
        Objects.requireNonNull(endpointB, "endpointB");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(points, "points");
        Objects.requireNonNull(impulses, "impulses");
        Objects.requireNonNull(truncations, "truncations");
        if (points.size() > Box2dContactLimits.MAX_CONTACT_VALUES
                || impulses.size() > Box2dContactLimits.MAX_CONTACT_VALUES
                || truncations.size() > 2) {
            throw new IllegalArgumentException("contact record exceeds its hard bound");
        }
        points = List.copyOf(points);
        normal = Objects.requireNonNull(normal, "normal");
        impulses = List.copyOf(impulses);
        oldManifold = Objects.requireNonNull(oldManifold, "oldManifold");
        truncations = List.copyOf(truncations);
        requireTruncations(truncations, phase);
        if (occurrence <= 0) {
            throw new IllegalArgumentException("contact callback occurrence must be positive");
        }
        if (!key.matches(endpointA, endpointB)) {
            throw new IllegalArgumentException("contact key and endpoints disagree");
        }
        boolean endpointsOnly = phase == Phase.BEGIN || phase == Phase.END;
        if (endpointsOnly != (availability == Availability.ENDPOINTS_ONLY)
                || endpointsOnly && (!points.isEmpty() || normal.isPresent()
                        || !impulses.isEmpty() || oldManifold.isPresent())
                || phase == Phase.PRE_SOLVE
                        && (availability != Availability.CURRENT_AND_OLD_MANIFOLD
                                || oldManifold.isEmpty() || !impulses.isEmpty())
                || phase == Phase.POST_SOLVE
                        && (availability != Availability.CURRENT_MANIFOLD_AND_IMPULSES
                                || oldManifold.isPresent())) {
            throw new IllegalArgumentException("contact phase and available values disagree");
        }
    }

    @Override public int compareTo(Box2dContactRecord other) {
        int phaseOrder = phase.compareTo(other.phase);
        if (phaseOrder != 0) {
            return phaseOrder;
        }
        int keyOrder = key.compareTo(other.key);
        return keyOrder != 0 ? keyOrder : Long.compare(occurrence, other.occurrence);
    }

    /** Returns whether either immediately copied endpoint was a sensor. */
    public boolean sensor() {
        return endpointA.sensor() || endpointB.sensor();
    }

    /** Native callback phase. */
    public enum Phase {
        /** A contact began. */ BEGIN,
        /** A contact ended. */ END,
        /** Pre-solve callback. */ PRE_SOLVE,
        /** Post-solve callback. */ POST_SOLVE
    }

    /** Closed testimony about which phase-specific values were copied. */
    public enum Availability {
        /** Only endpoint and contact flags are available. */ ENDPOINTS_ONLY,
        /** Current and bounded old-manifold values are available. */ CURRENT_AND_OLD_MANIFOLD,
        /** Current manifold and bounded impulse values are available. */
        CURRENT_MANIFOLD_AND_IMPULSES
    }

    /** Closed Box2D manifold type. */
    public enum ManifoldType {
        /** Circle-to-circle manifold. */ CIRCLES,
        /** Face on canonical endpoint A. */ FACE_A,
        /** Face on canonical endpoint B. */ FACE_B
    }

    /** Stable registered endpoint plus copied sensor state. */
    public record Endpoint(String bodyId, String fixtureId, int childIndex, boolean sensor)
            implements Comparable<Endpoint> {
        /** Validates application IDs and a non-negative child index. */
        public Endpoint {
            validateId(bodyId, "bodyId");
            validateId(fixtureId, "fixtureId");
            if (childIndex < 0) {
                throw new IllegalArgumentException("childIndex must be non-negative");
            }
        }

        @Override public int compareTo(Endpoint other) {
            int fixtureOrder = fixtureId.compareTo(other.fixtureId);
            return fixtureOrder != 0 ? fixtureOrder : Integer.compare(childIndex, other.childIndex);
        }
    }

    /** Canonically ordered stable fixture/child contact identity. */
    public record Key(String fixtureAId, int childIndexA, String fixtureBId, int childIndexB)
            implements Comparable<Key> {
        /** Requires strict canonical fixture/child order. */
        public Key {
            validateId(fixtureAId, "fixtureAId");
            validateId(fixtureBId, "fixtureBId");
            if (childIndexA < 0 || childIndexB < 0
                    || compare(fixtureAId, childIndexA, fixtureBId, childIndexB) >= 0) {
                throw new IllegalArgumentException("contact endpoints are not canonically ordered");
            }
        }

        @Override public int compareTo(Key other) {
            int first = compare(fixtureAId, childIndexA,
                    other.fixtureAId, other.childIndexA);
            return first != 0 ? first : compare(fixtureBId, childIndexB,
                    other.fixtureBId, other.childIndexB);
        }

        /** Returns whether copied endpoints have exactly this stable fixture/child identity. */
        public boolean matches(Endpoint first, Endpoint second) {
            return fixtureAId.equals(first.fixtureId) && childIndexA == first.childIndex
                    && fixtureBId.equals(second.fixtureId) && childIndexB == second.childIndex;
        }

        private static int compare(String firstId, int firstChild,
                String secondId, int secondChild) {
            int fixtureOrder = firstId.compareTo(secondId);
            return fixtureOrder != 0 ? fixtureOrder : Integer.compare(firstChild, secondChild);
        }
    }

    /** Copied normal magnitude and signed tangent impulse. */
    public record Impulse(double normal, double tangent) {
        /** Rejects non-finite or negative normal impulse evidence. */
        public Impulse {
            if (!Double.isFinite(normal) || normal < 0 || !Double.isFinite(tangent)) {
                throw new IllegalArgumentException("contact impulse must be finite");
            }
        }
    }

    /** Copied old-manifold point identifier and warm-start impulses. */
    public record OldManifoldPoint(long id, double normalImpulse, double tangentImpulse) {
        /** Rejects invalid unsigned IDs and non-finite impulse values. */
        public OldManifoldPoint {
            if (id < 0 || id > 0xffff_ffffL || !Double.isFinite(normalImpulse)
                    || normalImpulse < 0 || !Double.isFinite(tangentImpulse)) {
                throw new IllegalArgumentException("old manifold point is invalid");
            }
        }
    }

    /** Bounded copied old-manifold values. */
    public record OldManifold(ManifoldType type, List<OldManifoldPoint> points) {
        /** Defensively copies old points. */
        public OldManifold {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(points, "points");
            if (points.size() > Box2dContactLimits.MAX_CONTACT_VALUES) {
                throw new IllegalArgumentException("old manifold exceeds its hard bound");
            }
            points = List.copyOf(points);
        }
    }

    private static void requireTruncations(List<Truncation> values, Phase phase) {
        String previous = null;
        for (Truncation value : values) {
            String dimension = value.dimension();
            boolean supported = (phase == Phase.PRE_SOLVE || phase == Phase.POST_SOLVE)
                            && dimension.equals("box2d.contact.points")
                    || phase == Phase.POST_SOLVE
                            && dimension.equals("box2d.contact.impulses")
                    || phase == Phase.PRE_SOLVE
                            && dimension.equals("box2d.contact.oldManifoldPoints");
            if (!supported || previous != null && previous.compareTo(dimension) >= 0) {
                throw new IllegalArgumentException(
                        "contact record truncations are not closed and sorted");
            }
            previous = dimension;
        }
    }

    private static void validateId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 220) {
            throw new IllegalArgumentException(name + " is outside range");
        }
    }
}
