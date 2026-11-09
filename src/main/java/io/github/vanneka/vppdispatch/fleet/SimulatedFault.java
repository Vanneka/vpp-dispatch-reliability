package io.github.vanneka.vppdispatch.fleet;

/** Faults that can be injected into a simulated device for testing. */
public enum SimulatedFault {
    /** The device behaves normally. */
    NONE,
    /** The device accepts and holds the command, but its response never arrives. */
    DROP_RESPONSE
}
