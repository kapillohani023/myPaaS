package io.github.kapillohani023.mypaas.core;

/** Masked not-found. Unauthorized callers must not learn that a resource exists. */
public class NotFoundException extends DomainException {
    public NotFoundException(String what) {
        super("not_found", 404, what + " not found");
    }
}
