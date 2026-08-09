package io.github.teemuki8.libgdx.agent.runtime.box2d;

/** Hard bounds for explicit Box2D registrations and captured adapter values. */
public record Box2dAdapterLimits(int worlds, int bodies, int fixtures, int joints,
        int shapeVertices, int propertiesPerEntity, int diagnosticEntries) {
    private static final int MAX_REGISTRATIONS = 1_000_000;
    private static final int REQUIRED_SCHEMA_PROPERTIES = 20;

    /** Validates positive bounded limits and the required closed schema capacity. */
    public Box2dAdapterLimits {
        if (worlds <= 0 || worlds > MAX_REGISTRATIONS
                || bodies <= 0 || bodies > MAX_REGISTRATIONS
                || fixtures <= 0 || fixtures > MAX_REGISTRATIONS
                || joints <= 0 || joints > MAX_REGISTRATIONS
                || shapeVertices <= 0 || shapeVertices > MAX_REGISTRATIONS
                || propertiesPerEntity < REQUIRED_SCHEMA_PROPERTIES || propertiesPerEntity > 256
                || diagnosticEntries <= 0 || diagnosticEntries > 64) {
            throw new IllegalArgumentException("Box2D adapter limit is outside the supported range");
        }
    }

    /** Returns conservative development limits. */
    public static Box2dAdapterLimits developmentDefaults() {
        return new Box2dAdapterLimits(16, 4_096, 8_192, 2_048, 64, 64, 8);
    }
}
