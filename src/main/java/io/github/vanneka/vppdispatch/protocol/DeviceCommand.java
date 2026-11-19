package io.github.vanneka.vppdispatch.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One device's share of a dispatch: "deliver targetKw from startTime for duration."
 *
 * <p>Device commands are internal to the platform. CSIP leaves how an aggregator
 * activates a control on its devices "outside the scope of CSIP" (CSIP v2.1, §6.2.4),
 * so this is not an IEEE 2030.5 resource.
 *
 * <p>The command ID is {@code mrid:deviceId}: built from the utility's event ID and the
 * device ID, never from the platform's clock. Any platform server that reads the same
 * event gets the same ID, so a command sent again after a restart is recognized as the
 * same command. Together with an absolute value and an absolute start time, this makes
 * a command safe to send twice.
 */
public record DeviceCommand(
        String commandId,
        String mrid,
        String deviceId,
        int targetKw,
        Instant startTime,
        Duration duration) {

    public DeviceCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(mrid, "mrid");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(duration, "duration");
        if (targetKw <= 0) {
            throw new IllegalArgumentException("targetKw must be positive");
        }
    }

    /** The command ID for a device in an event: {@code mrid:deviceId}. */
    public static String idFor(String mrid, String deviceId) {
        return mrid + ":" + deviceId;
    }

    /** Creates a command whose ID is derived from the event and the device. */
    public static DeviceCommand forDevice(DispatchRequest request, String deviceId, int targetKw) {
        return new DeviceCommand(
                idFor(request.mrid(), deviceId), request.mrid(), deviceId, targetKw,
                request.startTime(), request.duration());
    }
}
