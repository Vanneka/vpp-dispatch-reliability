package io.github.vanneka.vppdispatch.protocol;

import java.util.Objects;

/**
 * A device's reply to a command. In v0.1.0 a device only confirms that it
 * received the command and is holding it for the start time. Later releases
 * add more stages (started, completed, cancelled).
 */
public record DeviceResponse(String commandId, String deviceId, ResponseStatus status) {

    public DeviceResponse {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(status, "status");
    }
}
