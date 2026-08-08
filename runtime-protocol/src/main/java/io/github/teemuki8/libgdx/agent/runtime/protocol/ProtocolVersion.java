package io.github.teemuki8.libgdx.agent.runtime.protocol;

/** Stable transport-neutral protocol version. */
public record ProtocolVersion(int major, int minor) {
    /** Frozen original read-only protocol version. */
    public static final ProtocolVersion V1 = new ProtocolVersion(1, 0);
    /** Extension-aware capabilities protocol version. */
    public static final ProtocolVersion V1_1 = new ProtocolVersion(1, 1);
    /** Application-owned command dispatch protocol version. */
    public static final ProtocolVersion V1_2 = new ProtocolVersion(1, 2);
    /** Execution-epoch and baseline protocol version. */
    public static final ProtocolVersion V1_3 = new ProtocolVersion(1, 3);
    /** Explicit resettable-scenario protocol version. */
    public static final ProtocolVersion V1_4 = new ProtocolVersion(1, 4);
    /** Explicit fact-attribution and metadata-query protocol version. */
    public static final ProtocolVersion V1_5 = new ProtocolVersion(1, 5);
    /** Explicit typed semantic-action protocol version. */
    public static final ProtocolVersion V1_6 = new ProtocolVersion(1, 6);
    /** Closed declarative runtime assertion protocol version. */
    public static final ProtocolVersion V1_7 = new ProtocolVersion(1, 7);
    /** Application-owned bounded simulation-control protocol version. */
    public static final ProtocolVersion V1_8 = new ProtocolVersion(1, 8);
    /** Explicit registered input scheduling protocol version. */
    public static final ProtocolVersion V1_9 = new ProtocolVersion(1, 9);
    /** Application-owned opaque checkpoint protocol version. */
    public static final ProtocolVersion V1_10 = new ProtocolVersion(1, 10);
    /** Explicit runtime-to-UI binding and frame-correlation protocol version. */
    public static final ProtocolVersion V1_11 = new ProtocolVersion(1, 11);
    /** Bounded input and execution recording protocol version. */
    public static final ProtocolVersion V1_12 = new ProtocolVersion(1, 12);
    /** Bounded repeated-scenario determinism comparison protocol version. */
    public static final ProtocolVersion V1_13 = new ProtocolVersion(1, 13);
    /** Additive removed-entity-history and structured failure-evidence protocol version. */
    public static final ProtocolVersion V2 = new ProtocolVersion(2, 0);
    /** Latest implemented protocol version. */
    public static final ProtocolVersion CURRENT = V2;

    /** Validates version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("protocol version must be non-negative");
        }
    }

    /**
     * Returns whether this version satisfies a V1 minor threshold. Every V1 path keeps its exact
     * {@code minor >= N} arithmetic; protocol 2.0 (major 2) satisfies every threshold regardless
     * of its zero minor.
     */
    public boolean atLeast(int minor) {
        return major() >= 2 || minor() >= minor;
    }

    /** Returns whether this version is the additive protocol 2.0 family. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isV2() {
        return major() >= 2;
    }

    /**
     * Returns whether this version supports one protocol command. V2.0 enables every existing
     * V1.13 command despite {@code minor == 0}, plus the additive {@code runtime_entity_history}
     * command and structured failure-evidence shape.
     */
    public boolean capability(RuntimeCommand command) {
        if (isV2()) {
            return true;
        }
        if (major() != 1) {
            return false;
        }
        return switch (command) {
            case RuntimeCommand.EntityHistory _ -> false;
            case RuntimeCommand.CommandStatus _, RuntimeCommand.CommandCancel _ -> minor() >= 2;
            case RuntimeCommand.EpochFrames _ -> minor() >= 3;
            case RuntimeCommand.Scenarios _, RuntimeCommand.Reset _ -> minor() >= 4;
            case RuntimeCommand.AttributedChanges _, RuntimeCommand.AttributedEvents _,
                    RuntimeCommand.AttributedDecisions _ -> minor() >= 5;
            case RuntimeCommand.Actions _, RuntimeCommand.Action _ -> minor() >= 6;
            case RuntimeCommand.Assert _ -> minor() >= 7;
            case RuntimeCommand.Control _, RuntimeCommand.Advance _, RuntimeCommand.Wait _ ->
                    minor() >= 8;
            case RuntimeCommand.Inputs _, RuntimeCommand.Input _ -> minor() >= 9;
            case RuntimeCommand.Checkpoints _, RuntimeCommand.CheckpointCreate _,
                    RuntimeCommand.CheckpointRestore _ -> minor() >= 10;
            case RuntimeCommand.UiBindings _, RuntimeCommand.UiFrames _ -> minor() >= 11;
            case RuntimeCommand.RecordingStart _, RuntimeCommand.RecordingStop _,
                    RuntimeCommand.RecordingGet _ -> minor() >= 12;
            case RuntimeCommand.DeterminismCheck _ -> minor() >= 13;
            default -> true;
        };
    }

    /** Returns the exact required-version message for one unsupported command. */
    public String requiredVersionMessage(RuntimeCommand command) {
        return switch (command) {
            case RuntimeCommand.EntityHistory _ -> "command requires protocol version 2.0";
            case RuntimeCommand.CommandStatus _, RuntimeCommand.CommandCancel _ ->
                    "command requires protocol version 1.2";
            case RuntimeCommand.EpochFrames _ -> "command requires protocol version 1.3";
            case RuntimeCommand.Scenarios _, RuntimeCommand.Reset _ ->
                    "command requires protocol version 1.4";
            case RuntimeCommand.AttributedChanges _, RuntimeCommand.AttributedEvents _,
                    RuntimeCommand.AttributedDecisions _ -> "command requires protocol version 1.5";
            case RuntimeCommand.Actions _, RuntimeCommand.Action _ ->
                    "command requires protocol version 1.6";
            case RuntimeCommand.Assert _ -> "command requires protocol version 1.7";
            case RuntimeCommand.Control _, RuntimeCommand.Advance _, RuntimeCommand.Wait _ ->
                    "command requires protocol version 1.8";
            case RuntimeCommand.Inputs _, RuntimeCommand.Input _ ->
                    "command requires protocol version 1.9";
            case RuntimeCommand.Checkpoints _, RuntimeCommand.CheckpointCreate _,
                    RuntimeCommand.CheckpointRestore _ -> "command requires protocol version 1.10";
            case RuntimeCommand.UiBindings _, RuntimeCommand.UiFrames _ ->
                    "command requires protocol version 1.11";
            case RuntimeCommand.RecordingStart _, RuntimeCommand.RecordingStop _,
                    RuntimeCommand.RecordingGet _ -> "command requires protocol version 1.12";
            case RuntimeCommand.DeterminismCheck _ -> "command requires protocol version 1.13";
            default -> "command requires protocol version 1.0";
        };
    }
}
