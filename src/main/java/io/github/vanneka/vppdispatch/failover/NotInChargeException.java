package io.github.vanneka.vppdispatch.failover;

/** Thrown when an server that does not hold the lease is asked to start a dispatch. */
public final class NotInChargeException extends RuntimeException {

    public NotInChargeException(String message) {
        super(message);
    }
}
