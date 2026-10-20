package io.github.kapillohani023.mypaas.core;

public class ConflictException extends DomainException {
    public ConflictException(String code, String message) {
        super(code, 409, message);
    }
}
