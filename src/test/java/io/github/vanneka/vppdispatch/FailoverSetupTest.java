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
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.CommandState;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sets the stage for failover: a primary and a backup share one database file, a lease
 * decides which one is in charge, a manual clock moves time, and a simulated crash stops
 * the primary at a chosen moment. Recovering the half-finished dispatch is a later step.
 */
class FailoverSetupTest {

    private static final Instant FIVE_PM = Instant.parse("2026-07-15T21:00:00Z");
    private static final DispatchRequest EVENT = new DispatchRequest(
            "EVENT0001", Instant.parse("2026-07-15T18:00:00Z"), 12, FIVE_PM, Duration.ofHours(2));

    @TempDir
    Path tempDir;

    private ManualClock clock;
    private SqliteLease primaryLease;
    private SqliteLease backupLease;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2026-07-15T20:55:00Z"));
        Path databaseFile = tempDir.resolve("progress.db");
        primaryLease = new SqliteLease(databaseFile, "primary", clock);
        backupLease = new SqliteLease(databaseFile, "backup", clock);
    }

    @AfterEach
    void tearDown() {
        primaryLease.close();
        backupLease.close();
    }

    private static SimulatedFleet threeBatteries() {
        return new SimulatedFleet()
                .add(new SimulatedDevice("battery-1", 5))
                .add(new SimulatedDevice("battery-2", 5))
                .add(new SimulatedDevice("battery-3", 4));
    }

    private CommandState stateOf(SqliteDispatchStore store, String deviceId) {
        String commandId = DeviceCommand.idFor(EVENT.mrid(), deviceId);
        return store.commandsFor(EVENT.mrid()).stream()
                .filter(c -> c.command().commandId().equals(commandId))
                .findFirst()
                .orElseThrow()
                .state();
    }

    @Test
    void backupDoesNotTakeOver_whilePrimaryKeepsRenewing() {
        assertTrue(primaryLease.tryAcquireOrRenew());

        // The primary renews every few seconds (its heartbeat) for half a minute.
        for (int i = 0; i < 10; i++) {
            clock.advance(SqliteLease.RENEW_EVERY);
            assertTrue(primaryLease.tryAcquireOrRenew());
            assertFalse(backupLease.tryAcquireOrRenew());
        }
        assertTrue(primaryLease.holdsLease());
        assertFalse(backupLease.holdsLease());
    }

    @Test
    void backupTakesOver_onceThePrimaryStopsRenewingAndTheLeaseExpires() {
        assertTrue(primaryLease.tryAcquireOrRenew());

        // The primary stops renewing. Just before expiry, the backup still can't take over.
        clock.advance(SqliteLease.LEASE_DURATION.minusSeconds(1));
        assertFalse(backupLease.tryAcquireOrRenew());

        // Once the lease has expired, the backup takes over: a failover.
        clock.advance(Duration.ofSeconds(2));
        assertTrue(backupLease.tryAcquireOrRenew());
        assertTrue(backupLease.holdsLease());
        assertFalse(primaryLease.holdsLease());
    }

    @Test
    void primaryThatLostTheLease_cannotStartADispatch() {
        assertTrue(primaryLease.tryAcquireOrRenew());
        clock.advance(SqliteLease.LEASE_DURATION.plusSeconds(1));
        assertTrue(backupLease.tryAcquireOrRenew());

        SimulatedFleet fleet = threeBatteries();
        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchCoordinator oldPrimary =
                    new DispatchCoordinator(fleet, store, primaryLease, SimulatedCrash.none());

            assertThrows(NotInChargeException.class, () -> oldPrimary.run(EVENT));
            assertFalse(store.hasDispatch(EVENT.mrid()));
        }
        assertEquals(0, fleet.device("battery-1").commandsReceived());
    }

    @Test
    void crashAfterSendingBeforeRecording_deviceHasTheCommandButRecordSaysPlanned() {
        assertTrue(primaryLease.tryAcquireOrRenew());
        SimulatedFleet fleet = threeBatteries();

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchCoordinator primary = new DispatchCoordinator(
                    fleet, store, primaryLease, SimulatedCrash.afterSendingCommand(0));

            assertThrows(SimulatedCrashException.class, () -> primary.run(EVENT));

            // The gap from the blog's fourth post: the device has it, the record doesn't know.
            assertTrue(fleet.device("battery-1").heldCommand().isPresent());
            assertEquals(CommandState.PLANNED, stateOf(store, "battery-1"));
            // Nothing after the crash was sent.
            assertEquals(0, fleet.device("battery-2").commandsReceived());
        }
    }

    @Test
    void crashPartwayThroughThePlan_leavesSomeCommandsRecordedAndSomeNot() {
        assertTrue(primaryLease.tryAcquireOrRenew());
        SimulatedFleet fleet = threeBatteries();

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchCoordinator primary = new DispatchCoordinator(
                    fleet, store, primaryLease, SimulatedCrash.afterSendingCommand(1));

            assertThrows(SimulatedCrashException.class, () -> primary.run(EVENT));

            // Plan (largest first): battery-1 5 kW, battery-2 5 kW, battery-3 2 kW.
            assertEquals(CommandState.RECEIVED, stateOf(store, "battery-1"));
            assertEquals(CommandState.PLANNED, stateOf(store, "battery-2")); // sent, not recorded
            assertTrue(fleet.device("battery-2").heldCommand().isPresent());
            assertEquals(CommandState.PLANNED, stateOf(store, "battery-3")); // never sent
            assertEquals(0, fleet.device("battery-3").commandsReceived());
        }
    }
}
