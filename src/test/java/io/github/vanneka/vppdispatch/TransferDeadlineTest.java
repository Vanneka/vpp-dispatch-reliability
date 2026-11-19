package io.github.vanneka.vppdispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vanneka.vppdispatch.failover.SimulatedCrash;
import io.github.vanneka.vppdispatch.failover.SimulatedCrashException;
import io.github.vanneka.vppdispatch.failover.SqliteLease;
import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.utility.SimulatedUtilityServer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CSIP's transfer limit: an aggregator "SHALL transfer the DER control to the DERs within
 * 15 minutes of receiving the control from the server" (CSIP v2.1, §4.5). These tests
 * measure time on a manual clock, so sending takes no time at all. They show that the
 * recovery steps fit inside the limit; they say nothing about real network or device timing.
 */
class TransferDeadlineTest {

    private static final DispatchRequest EVENT = new DispatchRequest(
            "EVENT0001", Instant.parse("2026-07-15T18:00:00Z"), 12,
            Instant.parse("2026-07-15T21:00:00Z"), Duration.ofHours(2));

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
        clock = new ManualClock(Instant.parse("2026-07-15T20:00:00Z"));
        Path databaseFile = tempDir.resolve("progress.db");
        primaryLease = new SqliteLease(databaseFile, "primary", clock);
        backupLease = new SqliteLease(databaseFile, "backup", clock);
        primaryStore = new SqliteDispatchStore(databaseFile);
        backupStore = new SqliteDispatchStore(databaseFile);
        utility = new SimulatedUtilityServer();
        fleet = new SimulatedFleet()
                .add(new SimulatedDevice("battery-1", 5))
                .add(new SimulatedDevice("battery-2", 5))
                .add(new SimulatedDevice("battery-3", 4));

        assertTrue(primaryLease.tryAcquireOrRenew());
        utility.issue(EVENT);
    }

    @AfterEach
    void tearDown() {
        primaryStore.close();
        backupStore.close();
        primaryLease.close();
        backupLease.close();
    }

    private DispatchCoordinator primary(SimulatedCrash crash) {
        return new DispatchCoordinator(fleet, primaryStore, primaryLease, crash, clock);
    }

    private DispatchCoordinator backup() {
        return new DispatchCoordinator(fleet, backupStore, backupLease, SimulatedCrash.none(), clock);
    }

    /** The primary crashes partway through the plan, before recording its second send. */
    private void primaryCrashesPartway() {
        assertThrows(SimulatedCrashException.class,
                () -> primary(SimulatedCrash.afterSendingCommand(1)).run(EVENT));
    }

    @Test
    void dispatchWithoutAFailover_isSentWithinTheLimit() {
        primary(SimulatedCrash.none()).run(EVENT);

        assertEquals(Optional.of(Duration.ZERO), backup().timeToSendAll(EVENT.mrid()));
        assertTrue(backup().sentWithin(EVENT.mrid(), DispatchCoordinator.CSIP_TRANSFER_LIMIT));
    }

    @Test
    void crashThenFailover_everyCommandIsStillSentWithinTheLimit() {
        primaryCrashesPartway();

        // The lease expires after 10 seconds; the backup takes it one second later.
        clock.advance(SqliteLease.LEASE_DURATION.plusSeconds(1));
        assertTrue(backupLease.tryAcquireOrRenew());
        backup().recoverAfterFailover(utility);

        assertEquals(Optional.of(Duration.ofSeconds(11)), backup().timeToSendAll(EVENT.mrid()));
        assertTrue(backup().sentWithin(EVENT.mrid(), DispatchCoordinator.CSIP_TRANSFER_LIMIT));
    }

    @Test
    void limitIsASetting_aShorterLimitIsReportedAsMissed() {
        primaryCrashesPartway();
        clock.advance(SqliteLease.LEASE_DURATION.plusSeconds(1));
        assertTrue(backupLease.tryAcquireOrRenew());
        backup().recoverAfterFailover(utility);

        assertFalse(backup().sentWithin(EVENT.mrid(), Duration.ofSeconds(5)));
    }

    @Test
    void backupThatRecoversTooLate_isReportedAsMissingTheLimit() {
        primaryCrashesPartway();

        // No backup is ready until 20 minutes later.
        clock.advance(Duration.ofMinutes(20));
        assertTrue(backupLease.tryAcquireOrRenew());
        backup().recoverAfterFailover(utility);

        assertEquals(Optional.of(Duration.ofMinutes(20)), backup().timeToSendAll(EVENT.mrid()));
        assertFalse(backup().sentWithin(EVENT.mrid(), DispatchCoordinator.CSIP_TRANSFER_LIMIT));
    }

    @Test
    void commandNeverSent_isReportedAsMissingTheLimit() {
        primaryCrashesPartway(); // no recovery: battery-2 and battery-3 have no recorded send

        assertEquals(Optional.empty(), backup().timeToSendAll(EVENT.mrid()));
        assertFalse(backup().sentWithin(EVENT.mrid(), DispatchCoordinator.CSIP_TRANSFER_LIMIT));
    }
}
