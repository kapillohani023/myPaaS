package io.github.kapillohani023.mypaas.core;

import java.util.List;

public class ValidationException extends DomainException {

    private final List<String> violations;

    public ValidationException(String message) {
        this(List.of(message));
    }

    public ValidationException(List<String> violations) {
        super("validation_failed", 422, String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
