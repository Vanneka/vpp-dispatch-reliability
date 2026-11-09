package io.github.vanneka.vppdispatch.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One device's share of a dispatch: "discharge targetKw from startTime for duration."
 *
 * <p>The command ID is built from the dispatch ID and the device ID, so the same
 * device in the same dispatch always gets the same ID. Later releases rely on this
 * to recognize a command that is sent again after a restart.
 */
public record DeviceCommand(
        String commandId,
        String dispatchId,
        String deviceId,
        int targetKw,
        Instant startTime,
        Duration duration) {

    public DeviceCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(dispatchId, "dispatchId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(duration, "duration");
        if (targetKw <= 0) {
            throw new IllegalArgumentException("targetKw must be positive");
        }
    }

    /** Creates a command whose ID is derived from the dispatch and device. */
    public static DeviceCommand forDevice(DispatchRequest request, String deviceId, int targetKw) {
        String commandId = request.dispatchId() + ":" + deviceId;
        return new DeviceCommand(
                commandId, request.dispatchId(), deviceId, targetKw, request.startTime(), request.duration());
    }
}
