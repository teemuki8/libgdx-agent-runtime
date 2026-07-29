package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;
import java.util.Optional;

/** Explicit or unknown attribution supplied for a captured difference. */
public record ChangeCause(
        Kind kind,
        Optional<String> semanticCode,
        Optional<EventId> eventId,
        Optional<DecisionId> decisionId) {
    /** Supported attribution forms. */
    public enum Kind {
        /** Snapshot comparison alone supplies no cause. */
        UNKNOWN,
        /** Game code supplied a stable semantic code. */
        SEMANTIC,
        /** Game code associated an emitted event. */
        EVENT,
        /** Game code associated a decision trace. */
        DECISION
    }

    /** Validates that only the selected attribution is populated. */
    public ChangeCause {
        Objects.requireNonNull(kind, "kind");
        semanticCode = Objects.requireNonNull(semanticCode, "semanticCode");
        eventId = Objects.requireNonNull(eventId, "eventId");
        decisionId = Objects.requireNonNull(decisionId, "decisionId");
        semanticCode.ifPresent(code -> IdentifierSupport.validate(code, "semantic cause"));
        int populated = (semanticCode.isPresent() ? 1 : 0)
                + (eventId.isPresent() ? 1 : 0) + (decisionId.isPresent() ? 1 : 0);
        if ((kind == Kind.UNKNOWN && populated != 0) || (kind != Kind.UNKNOWN && populated != 1)) {
            throw new IllegalArgumentException("change cause does not match its kind");
        }
    }

    /** Returns an unattributed snapshot difference. */
    public static ChangeCause unknown() {
        return new ChangeCause(Kind.UNKNOWN, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Returns a game-supplied semantic cause. */
    public static ChangeCause semantic(String code) {
        return new ChangeCause(Kind.SEMANTIC, Optional.of(code), Optional.empty(), Optional.empty());
    }

    /** Returns an explicit event association. */
    public static ChangeCause event(EventId id) {
        return new ChangeCause(Kind.EVENT, Optional.empty(), Optional.of(id), Optional.empty());
    }

    /** Returns an explicit decision association. */
    public static ChangeCause decision(DecisionId id) {
        return new ChangeCause(Kind.DECISION, Optional.empty(), Optional.empty(), Optional.of(id));
    }
}
