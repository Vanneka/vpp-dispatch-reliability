package io.github.vanneka.vppdispatch.storage;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable record of dispatch progress: which devices were chosen, which commands
 * were sent, and which were confirmed. Keeping this outside the running process is
 * what lets a backup server pick up a dispatch in progress.
 */
public interface DispatchStore extends AutoCloseable {

    /**
     * Saves a dispatch and all of its planned commands together, or none of them.
     *
     * @param receivedAt when the platform received the event (the start of CSIP's
     *     15-minute transfer limit)
     */
    void saveDispatch(DispatchRequest request, List<DeviceCommand> commands, Instant receivedAt);

    /** When the platform received this event, as saved with the dispatch. */
    Instant receivedAt(String mrid);

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

    /** Like {@link #markSent(String)}, and also saves {@code sentAt} as the command's latest send time. */
    boolean markSent(String commandId, Instant sentAt);

    /** When the command was last recorded as sent, or empty if it never was. */
    Optional<Instant> lastSentAt(String commandId);

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
