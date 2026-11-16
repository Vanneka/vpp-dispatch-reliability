package io.github.vanneka.vppdispatch.failover;

/** Thrown when a {@link SimulatedCrash} stops a platform instance. Tests only. */
public final class SimulatedCrashException extends RuntimeException {

    public SimulatedCrashException(int commandIndex) {
        super("simulated crash after sending command " + commandIndex + ", before recording it");
    }
}
