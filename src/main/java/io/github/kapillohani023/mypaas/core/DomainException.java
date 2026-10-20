package io.github.kapillohani023.mypaas.core;

/**
 * Base of every expected domain failure. Adapters translate these to the API
 * error format; implementations must never leak secrets in messages.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final int status;

    public DomainException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public DomainException(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
