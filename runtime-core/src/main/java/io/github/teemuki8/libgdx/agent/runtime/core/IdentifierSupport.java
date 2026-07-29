package io.github.teemuki8.libgdx.agent.runtime.core;

import java.util.Objects;

final class IdentifierSupport {
    static final int MAX_LENGTH = 256;

    private IdentifierSupport() {}

    static String validate(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_LENGTH + " characters");
        }
        return value;
    }
}
