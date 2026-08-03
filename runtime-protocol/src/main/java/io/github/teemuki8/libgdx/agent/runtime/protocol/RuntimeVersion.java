package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Runtime library version reported by protocol and MCP surfaces. */
public final class RuntimeVersion {
    private static final String CURRENT = currentImplementationVersion();

    private RuntimeVersion() {}

    /** Returns the artifact implementation version or a stable development marker. */
    public static String current() {
        return CURRENT;
    }

    private static String currentImplementationVersion() {
        String value = RuntimeVersion.class.getPackage().getImplementationVersion();
        return value == null || value.isBlank() ? "development" : value;
    }
}
