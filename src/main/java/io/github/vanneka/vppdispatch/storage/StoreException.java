package io.github.vanneka.vppdispatch.storage;

/** Thrown when dispatch progress cannot be read from or written to storage. */
public final class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
