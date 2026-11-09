package io.github.vanneka.vppdispatch.storage;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import java.util.Objects;

/** A device command together with the progress the platform has recorded for it. */
public record StoredCommand(DeviceCommand command, CommandState state) {

    public StoredCommand {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
    }
}
