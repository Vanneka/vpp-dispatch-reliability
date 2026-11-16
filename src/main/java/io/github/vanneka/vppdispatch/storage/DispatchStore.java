package io.github.vanneka.vppdispatch.storage;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import java.util.List;

/**
 * Durable record of dispatch progress: which devices were chosen, which commands
 * were sent, and which were confirmed. Keeping this outside the running process is
 * what lets a backup instance pick up a dispatch in progress.
 */
public interface DispatchStore extends AutoCloseable {

    /** Saves a dispatch and all of its planned commands together, or none of them. */
    void saveDispatch(DispatchRequest request, List<DeviceCommand> commands);

    /** Whether a dispatch with this event ID (mRID) has already been saved. */
    boolean hasDispatch(String mrid);

    /**
     * Records that a command was sent. A command's recorded state only moves forward
     * (PLANNED, then SENT, then RECEIVED), so this has no effect on a command that is
     * already SENT or RECEIVED.
     *
     * @return {@code true} if the record moved forward, {@code false} if it was
     *     already at this state or later
     * @throws StoreException if no command has this ID
     */
    boolean markSent(String commandId);

    /**
     * Records that the device's control unit confirmed it has the command. Allowed
     * from PLANNED as well as SENT: the confirmation proves the command arrived even
     * if recording SENT never happened. Has no effect on a command already RECEIVED.
     *
     * @return {@code true} if the record moved forward, {@code false} if it was
     *     already RECEIVED
     * @throws StoreException if no command has this ID
     */
    boolean markReceived(String commandId);

    /** All commands for a dispatch, in the order they were planned. */
    List<StoredCommand> commandsFor(String mrid);

    @Override
    void close();
}
