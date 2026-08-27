package io.github.teemuki8.libgdx.agent.runtime.box2d;

import io.github.teemuki8.libgdx.agent.runtime.core.Truncation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immediately copied and canonically oriented Box2D contact callback. */
public record Box2dContactRecord(Phase phase, Key key, Endpoint endpointA, Endpoint endpointB,
        boolean touching, boolean enabled, Availability availability, List<Box2dVector> points,
        Optional<Box2dVector> normal, List<Impulse> impulses,
        long occurrence, List<Truncation> truncations)
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
                        || !impulses.isEmpty())
                || phase == Phase.POST_SOLVE
                        && availability != Availability.CURRENT_MANIFOLD_AND_IMPULSES) {
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

    /** Box2D 3 post-step event phase. */
    public enum Phase {
        /** A contact began. */ BEGIN,
        /** A contact ended. */ END,
        /** A hit event copied as compatible post-solve evidence. */ POST_SOLVE
    }

    /** Closed testimony about which phase-specific values were copied. */
    public enum Availability {
        /** Only endpoint and contact flags are available. */ ENDPOINTS_ONLY,
        /** Hit point, normal, and whole-step impulses are available. */
        CURRENT_MANIFOLD_AND_IMPULSES
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


    private static void requireTruncations(List<Truncation> values, Phase phase) {
        String previous = null;
        for (Truncation value : values) {
            String dimension = value.dimension();
            boolean supported = phase == Phase.POST_SOLVE
                    && (dimension.equals("box2d.contact.points")
                            || dimension.equals("box2d.contact.impulses"));
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
