package io.github.kapillohani023.mypaas.core;

public class ForbiddenException extends DomainException {
    public ForbiddenException(String message) {
        super("forbidden", 403, message);
    }
}
