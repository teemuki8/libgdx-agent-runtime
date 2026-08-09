package io.github.teemuki8.libgdx.agent.runtime.box2d;

/** Hard bounds for Box2D callback, active-contact, diagnostic, and history evidence. */
public record Box2dContactLimits(int callbackRecordsPerTick, int activeContactsPerTick,
        int pointsPerContact, int impulsesPerContact, int oldManifoldPointsPerContact,
        int diagnosticsPerTick, int retainedContactTicks, int queryPageSize) {
    private static final int MAX_ITEMS = 1_000_000;
    private static final int MAX_CONTACT_VALUES = 64;
    private static final int MAX_DIAGNOSTICS = 64;

    /** Validates positive finite allocation bounds. */
    public Box2dContactLimits {
        if (callbackRecordsPerTick <= 0 || callbackRecordsPerTick > MAX_ITEMS
                || activeContactsPerTick <= 0 || activeContactsPerTick > MAX_ITEMS
                || pointsPerContact <= 0 || pointsPerContact > MAX_CONTACT_VALUES
                || impulsesPerContact <= 0 || impulsesPerContact > MAX_CONTACT_VALUES
                || oldManifoldPointsPerContact <= 0
                || oldManifoldPointsPerContact > MAX_CONTACT_VALUES
                || diagnosticsPerTick <= 0 || diagnosticsPerTick > MAX_DIAGNOSTICS
                || retainedContactTicks <= 0 || retainedContactTicks > MAX_ITEMS
                || queryPageSize <= 0 || queryPageSize > retainedContactTicks) {
            throw new IllegalArgumentException("Box2D contact limit is outside the supported range");
        }
    }

    /** Returns conservative defaults for ordinary Box2D worlds. */
    public static Box2dContactLimits developmentDefaults() {
        return new Box2dContactLimits(128, 256, 2, 2, 2, 8, 1_024, 256);
    }
}
