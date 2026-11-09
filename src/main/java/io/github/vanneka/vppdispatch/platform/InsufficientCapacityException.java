package io.github.vanneka.vppdispatch.platform;

/** Thrown when the fleet cannot cover the requested amount of power. */
public final class InsufficientCapacityException extends RuntimeException {

    public InsufficientCapacityException(int requestedKw, int availableKw) {
        super("requested " + requestedKw + " kW but only " + availableKw + " kW is available");
    }
}
