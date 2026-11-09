package io.github.vanneka.vppdispatch.fleet;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DeviceResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every device enrolled in the simulated VPP, whether or not it is chosen for a
 * given dispatch. The platform's {@code DeviceSelector} decides which of these take
 * part; the fleet is also how the platform reaches each device ({@link #send}).
 */
public final class SimulatedFleet {

    private final Map<String, SimulatedDevice> devices = new LinkedHashMap<>();

    public SimulatedFleet add(SimulatedDevice device) {
        if (devices.putIfAbsent(device.deviceId(), device) != null) {
            throw new IllegalArgumentException("duplicate device " + device.deviceId());
        }
        return this;
    }

    public List<SimulatedDevice> devices() {
        return Collections.unmodifiableList(new ArrayList<>(devices.values()));
    }

    public SimulatedDevice device(String deviceId) {
        SimulatedDevice device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("unknown device " + deviceId);
        }
        return device;
    }

    /** Sends a command to its device and returns the response, if one arrives. */
    public Optional<DeviceResponse> send(DeviceCommand command) {
        return device(command.deviceId()).receive(command);
    }
}
