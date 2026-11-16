package io.github.vanneka.vppdispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.CommandState;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.storage.StoreException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command's recorded state only moves forward: PLANNED, then SENT, then RECEIVED.
 * Late or repeated news never moves it back, so a backup reading the record after a
 * failover never sees a command go from "received" back to "sent."
 */
class CommandStateRecordTest {

    private static final String MRID = "EVENT0001";
    private static final String COMMAND_ID = DeviceCommand.idFor(MRID, "battery-1");

    @TempDir
    Path tempDir;

    private SqliteDispatchStore store;

    @BeforeEach
    void saveOneCommand() {
        store = new SqliteDispatchStore(tempDir.resolve("progress.db"));
        DispatchRequest request = new DispatchRequest(
                MRID, Instant.parse("2026-07-15T18:00:00Z"), 5,
                Instant.parse("2026-07-15T21:00:00Z"), Duration.ofHours(2));
        store.saveDispatch(request, List.of(DeviceCommand.forDevice(request, "battery-1", 5)));
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    private CommandState recordedState() {
        return store.commandsFor(MRID).get(0).state();
    }

    @Test
    void stateMovesForwardInOrder() {
        assertTrue(store.markSent(COMMAND_ID));
        assertEquals(CommandState.SENT, recordedState());
        assertTrue(store.markReceived(COMMAND_ID));
        assertEquals(CommandState.RECEIVED, recordedState());
    }

    @Test
    void lateSent_doesNotMoveAReceivedCommandBackward() {
        store.markSent(COMMAND_ID);
        store.markReceived(COMMAND_ID);

        assertFalse(store.markSent(COMMAND_ID));
        assertEquals(CommandState.RECEIVED, recordedState());
    }

    @Test
    void repeatedUpdates_haveNoFurtherEffect() {
        assertTrue(store.markSent(COMMAND_ID));
        assertFalse(store.markSent(COMMAND_ID));
        assertTrue(store.markReceived(COMMAND_ID));
        assertFalse(store.markReceived(COMMAND_ID));
        assertEquals(CommandState.RECEIVED, recordedState());
    }

    @Test
    void confirmationWithoutRecordedSend_stillCountsAsReceived() {
        // The platform sent the command but stopped before recording SENT; the
        // device's confirmation proves the command arrived.
        assertTrue(store.markReceived(COMMAND_ID));
        assertEquals(CommandState.RECEIVED, recordedState());
    }

    @Test
    void unknownCommand_isAnError() {
        assertThrows(StoreException.class, () -> store.markSent("NO-SUCH-EVENT:battery-9"));
    }
}
