package io.github.vanneka.vppdispatch.storage;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import java.util.List;

/**
 * Durable record of dispatch progress: which devices were chosen, which commands
 * were sent, and which responses came back. Keeping this outside the running
 * process is what later lets a backup instance pick up a dispatch in progress.
 */
public interface DispatchStore extends AutoCloseable {

    /** Saves a dispatch and all of its planned commands together, or none of them. */
    void saveDispatch(DispatchRequest request, List<DeviceCommand> commands);

    /** Records that a command was sent. */
    void markSent(String commandId);

    /** Records that a device's response to a command was received. */
    void markResponded(String commandId);

    /** All commands for a dispatch, in the order they were planned. */
    List<StoredCommand> commandsFor(String dispatchId);

    @Override
    void close();
}
