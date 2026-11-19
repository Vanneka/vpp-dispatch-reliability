package io.github.vanneka.vppdispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vanneka.vppdispatch.failover.NotInChargeException;
import io.github.vanneka.vppdispatch.failover.SimulatedCrash;
import io.github.vanneka.vppdispatch.failover.SimulatedCrashException;
import io.github.vanneka.vppdispatch.failover.SqliteLease;
import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.fleet.SimulatedFault;
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.platform.DispatchSummary;
import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.CommandState;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.storage.StoredCommand;
import io.github.vanneka.vppdispatch.utility.SimulatedUtilityServer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery after a failover: the primary stops partway through a dispatch, the backup
 * gets the lease, and it finishes the dispatch from the saved record. Simulation only.
 */
class RecoveryAfterFailoverTest {

    private static final DispatchRequest EVENT = new DispatchRequest(
            "EVENT0001", Instant.parse("2026-07-15T18:00:00Z"), 12,
            Instant.parse("2026-07-15T21:00:00Z"), Duration.ofHours(2));

    /** A second event the next day, issued while no server is in charge. */
    private static final DispatchRequest NEXT_DAY_EVENT = new DispatchRequest(
            "EVENT0002", Instant.parse("2026-07-16T18:00:00Z"), 3,
            Instant.parse("2026-07-16T21:00:00Z"), Duration.ofHours(2));

    @TempDir
    Path tempDir;

    private ManualClock clock;
    private SqliteLease primaryLease;
    private SqliteLease backupLease;
    private SqliteDispatchStore primaryStore;
    private SqliteDispatchStore backupStore;
    private SimulatedUtilityServer utility;
    private SimulatedFleet fleet;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-07-15T20:55:00Z"));
        Path databaseFile = tempDir.resolve("progress.db");
        primaryLease = new SqliteLease(databaseFile, "primary", clock);
        backupLease = new SqliteLease(databaseFile, "backup", clock);
        primaryStore = new SqliteDispatchStore(databaseFile);
        backupStore = new SqliteDispatchStore(databaseFile);
        utility = new SimulatedUtilityServer();

        // Plan for 12 kW (largest first): battery-1 5 kW, battery-2 5 kW, battery-3 2 kW.
        // battery-4 is enrolled but not needed, so it should never be sent anything.
        fleet = new SimulatedFleet()
                .add(new SimulatedDevice("battery-1", 5))
                .add(new SimulatedDevice("battery-2", 5))
                .add(new SimulatedDevice("battery-3", 4))
                .add(new SimulatedDevice("battery-4", 3));

        assertTrue(primaryLease.tryAcquireOrRenew());
    }

    @AfterEach
    void tearDown() {
        primaryStore.close();
        backupStore.close();
        primaryLease.close();
        backupLease.close();
    }

    /** The primary stops renewing; once its lease expires, the backup is in charge. */
    private void failover() {
        clock.advance(SqliteLease.LEASE_DURATION.plusSeconds(1));
        assertTrue(backupLease.tryAcquireOrRenew());
        assertFalse(primaryLease.holdsLease());
    }

    private DispatchCoordinator primaryThatCrashes(SimulatedCrash crash) {
        return new DispatchCoordinator(fleet, primaryStore, primaryLease, crash);
    }

    private DispatchCoordinator backup() {
        return new DispatchCoordinator(fleet, backupStore, backupLease, SimulatedCrash.none());
    }

    private StoredCommand recorded(String mrid, String deviceId) {
        String commandId = DeviceCommand.idFor(mrid, deviceId);
        return backupStore.commandsFor(mrid).stream()
                .filter(c -> c.command().commandId().equals(commandId))
                .findFirst()
                .orElseThrow();
    }

    private int timesSent(String deviceId) {
        return fleet.device(deviceId).commandsReceived();
    }

    @Test
    void crashAfterSendingBeforeRecording_backupResendsAndEachDeviceEndsWithTheSameCommand() {
        utility.issue(EVENT);
        assertThrows(SimulatedCrashException.class,
                () -> primaryThatCrashes(SimulatedCrash.afterSendingCommand(0)).run(EVENT));

        failover();
        List<DispatchSummary> summaries = backup().recoverAfterFailover(utility);

        assertEquals(1, summaries.size());
        assertEquals(12, summaries.get(0).confirmedKw());
        for (String deviceId : List.of("battery-1", "battery-2", "battery-3")) {
            StoredCommand entry = recorded(EVENT.mrid(), deviceId);
            assertEquals(CommandState.RECEIVED, entry.state());
            // One effective command: what the device holds is exactly the planned
            // command (same ID, same absolute value, same absolute time window).
            assertEquals(entry.command(), fleet.device(deviceId).heldCommand().orElseThrow());
        }
        // battery-1 was sent its command before the crash, and again by the backup,
        // because the record could not show that it had been sent.
        assertEquals(2, timesSent("battery-1"));
        assertEquals(1, timesSent("battery-2"));
        assertEquals(1, timesSent("battery-3"));
    }

    @Test
    void crashPartwayThroughThePlan_backupFinishesThePlanAndSendsNothingOutsideIt() {
        utility.issue(EVENT);
        assertThrows(SimulatedCrashException.class,
                () -> primaryThatCrashes(SimulatedCrash.afterSendingCommand(1)).run(EVENT));

        failover();
        backup().recoverAfterFailover(utility);

        assertEquals(1, timesSent("battery-1")); // already RECEIVED: not sent again
        assertEquals(2, timesSent("battery-2")); // sent but not recorded: sent again
        assertEquals(1, timesSent("battery-3")); // never sent: sent now
        assertEquals(0, timesSent("battery-4")); // not in the plan: never sent
        assertEquals(3, backupStore.commandsFor(EVENT.mrid()).size()); // plan unchanged
        assertEquals(12, backup().summarize(EVENT.mrid()).confirmedKw());
    }

    @Test
    void eventIssuedWhileNoInstanceWasInCharge_backupPicksItUpFromTheUtilitysList() {
        utility.issue(EVENT);
        primaryThatCrashes(SimulatedCrash.none()).run(EVENT);

        // The primary stops. Before the backup is in charge, the utility issues another
        // event; no server plans it, as if its notification were missed.
        utility.issue(NEXT_DAY_EVENT);
        failover();
        backup().recoverAfterFailover(utility);

        assertTrue(backupStore.hasDispatch(NEXT_DAY_EVENT.mrid()));
        assertEquals(3, backup().summarize(NEXT_DAY_EVENT.mrid()).confirmedKw());
        // The first event was already fully confirmed: nothing was sent for it again.
        assertEquals(1, timesSent("battery-2"));
        assertEquals(1, timesSent("battery-3"));
    }

    @Test
    void recoveringTwice_sendsNothingMoreOnceEverythingIsReceived() {
        utility.issue(EVENT);
        assertThrows(SimulatedCrashException.class,
                () -> primaryThatCrashes(SimulatedCrash.afterSendingCommand(0)).run(EVENT));
        failover();

        backup().recoverAfterFailover(utility);
        backup().recoverAfterFailover(utility);

        assertEquals(2, timesSent("battery-1"));
        assertEquals(1, timesSent("battery-2"));
        assertEquals(1, timesSent("battery-3"));
    }

    @Test
    void lostResponse_isSentAgainDuringRecoveryButStaysSentWithoutAConfirmation() {
        fleet.device("battery-2").injectFault(SimulatedFault.DROP_RESPONSE);
        utility.issue(EVENT);
        primaryThatCrashes(SimulatedCrash.none()).run(EVENT);

        failover();
        backup().recoverAfterFailover(utility);

        // The record cannot tell a lost response from a command that never arrived, so
        // the command is sent again. With no response, it stays SENT. Retrying with a
        // limit, and flagging commands that never respond, is planned for v0.4.0.
        assertEquals(2, timesSent("battery-2"));
        assertEquals(CommandState.SENT, recorded(EVENT.mrid(), "battery-2").state());
        assertEquals(1, timesSent("battery-1"));
    }

    @Test
    void instanceWithoutTheLease_cannotRecover() {
        utility.issue(EVENT);
        assertThrows(SimulatedCrashException.class,
                () -> primaryThatCrashes(SimulatedCrash.afterSendingCommand(0)).run(EVENT));
        failover();

        DispatchCoordinator oldPrimary =
                new DispatchCoordinator(fleet, primaryStore, primaryLease, SimulatedCrash.none());
        assertThrows(NotInChargeException.class, () -> oldPrimary.recoverAfterFailover(utility));
        assertEquals(1, timesSent("battery-1"));
        assertEquals(0, timesSent("battery-2"));
    }
}
