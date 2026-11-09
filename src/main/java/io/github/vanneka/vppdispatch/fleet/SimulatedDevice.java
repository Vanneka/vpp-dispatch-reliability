package io.github.vanneka.vppdispatch.fleet;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DeviceResponse;
import io.github.vanneka.vppdispatch.protocol.ResponseStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * A simulated home battery. It holds the latest command it received (devices
 * hold scheduled commands until the start time) and replies with a response,
 * unless a fault says the response is lost.
 *
 * <p>This is a simulation for testing the platform's behavior. It does not model
 * real battery physics.
 */
public final class SimulatedDevice {

    private final String deviceId;
    private final int availableKw;
    private SimulatedFault fault;
    private DeviceCommand heldCommand;

    public SimulatedDevice(String deviceId, int availableKw) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        if (availableKw <= 0) {
            throw new IllegalArgumentException("availableKw must be positive");
        }
        this.availableKw = availableKw;
        this.fault = SimulatedFault.NONE;
    }

    public String deviceId() {
        return deviceId;
    }

    public int availableKw() {
        return availableKw;
    }

    public void injectFault(SimulatedFault fault) {
        this.fault = Objects.requireNonNull(fault, "fault");
    }

    /** The command this device is holding, if any. */
    public Optional<DeviceCommand> heldCommand() {
        return Optional.ofNullable(heldCommand);
    }

    /**
     * Receives a command and returns the device's response. An empty result means
     * the response was lost on the way back, even though the device has the command.
     */
    public Optional<DeviceResponse> receive(DeviceCommand command) {
        if (!command.deviceId().equals(deviceId)) {
            throw new IllegalArgumentException(
                    "command for " + command.deviceId() + " sent to " + deviceId);
        }
        heldCommand = command;
        // No default branch on purpose: adding a new fault fails to compile here
        // until its behavior is decided.
        return switch (fault) {
            case NONE -> Optional.of(
                    new DeviceResponse(command.commandId(), deviceId, ResponseStatus.RECEIVED));
            case DROP_RESPONSE -> Optional.empty();
        };
    }
}
