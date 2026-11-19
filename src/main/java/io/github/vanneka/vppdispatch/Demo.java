package io.github.vanneka.vppdispatch;

import io.github.vanneka.vppdispatch.failover.SimulatedCrash;
import io.github.vanneka.vppdispatch.failover.SimulatedCrashException;
import io.github.vanneka.vppdispatch.failover.SqliteLease;
import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.fleet.SimulatedFault;
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.platform.DispatchSummary;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.DispatchStore;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.storage.StoredCommand;
import io.github.vanneka.vppdispatch.utility.SimulatedUtilityServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Runs one simulated dispatch with a failover and saves its progress to a SQLite file
 * you can open.
 *
 * <p>The primary stops right after sending a command and before recording it. Its lease
 * expires, the backup takes the lease, and the backup recovers the dispatch from the
 * saved record. Time is simulated, so the demo finishes at once.
 *
 * <p>Run with {@code ./gradlew run}. The database is written to {@code data/demo.db}, or
 * to the path given as the first argument, which must end in {@code .db}. Missing folders
 * are created, and any existing file at that path is replaced.
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) throws IOException {
        Path databaseFile = Path.of(args.length > 0 ? args[0] : "data/demo.db").toAbsolutePath();
        // The demo replaces the file it writes to, so only accept database files.
        if (!databaseFile.getFileName().toString().endsWith(".db")) {
            System.err.println("The database path must end in .db: " + databaseFile);
            System.exit(2);
        }
        Files.createDirectories(databaseFile.getParent());
        Files.deleteIfExists(databaseFile);

        SimulatedFleet fleet = new SimulatedFleet()
                .add(new SimulatedDevice("battery-1", 5))
                .add(new SimulatedDevice("battery-2", 5))
                .add(new SimulatedDevice("battery-3", 4))
                .add(new SimulatedDevice("battery-4", 3));
        // One device's response is lost on the way back, so the output also shows a
        // command that was sent but never confirmed.
        fleet.device("battery-3").injectFault(SimulatedFault.DROP_RESPONSE);

        // Fields modeled on an IEEE 2030.5 DERControl event: mRID, creation time,
        // and an absolute start time and duration.
        DispatchRequest event = new DispatchRequest(
                "DEMO0001", Instant.parse("2026-07-15T18:00:00Z"), 12,
                Instant.parse("2026-07-15T21:00:00Z"), Duration.ofHours(2));
        SimulatedUtilityServer utility = new SimulatedUtilityServer();
        utility.issue(event);

        SimulatedClock clock = new SimulatedClock(Instant.parse("2026-07-15T20:00:00Z"));

        try (SqliteLease primaryLease = new SqliteLease(databaseFile, "primary", clock);
                SqliteLease backupLease = new SqliteLease(databaseFile, "backup", clock);
                SqliteDispatchStore primaryStore = new SqliteDispatchStore(databaseFile);
                SqliteDispatchStore backupStore = new SqliteDispatchStore(databaseFile)) {

            System.out.println("Simulated dispatch " + event.mrid() + ": " + event.targetKw() + " kW requested");

            // 1. The primary starts the dispatch and stops after sending its second command.
            primaryLease.tryAcquireOrRenew();
            DispatchCoordinator primary = new DispatchCoordinator(
                    fleet, primaryStore, primaryLease, SimulatedCrash.afterSendingCommand(1), clock);
            try {
                primary.run(event);
            } catch (SimulatedCrashException stopped) {
                System.out.println();
                System.out.println("Primary stopped after sending to battery-2, before recording it:");
                printCommands(backupStore, event.mrid());
            }

            // 2. The primary's lease expires and the backup takes it: a failover.
            clock.advance(SqliteLease.LEASE_DURATION.plusSeconds(1));
            backupLease.tryAcquireOrRenew();

            // 3. The backup recovers the dispatch from the saved record.
            DispatchCoordinator backup = new DispatchCoordinator(
                    fleet, backupStore, backupLease, SimulatedCrash.none(), clock);
            backup.recoverAfterFailover(utility);
            DispatchSummary summary = backup.summarize(event.mrid());

            System.out.println();
            System.out.println("Backup took the lease 11 s later and recovered the dispatch:");
            printCommands(backupStore, event.mrid());
            System.out.printf("Planned %d kW, confirmed %d kW (%d of %d confirmations recorded)%n",
                    summary.plannedKw(), summary.confirmedKw(),
                    summary.confirmationsRecorded(), summary.commandsPlanned());
            backup.timeToSendAll(event.mrid()).ifPresent(time -> System.out.printf(
                    "Every command sent %d s after the event was received (CSIP limit: %d min)%n",
                    time.toSeconds(), DispatchCoordinator.CSIP_TRANSFER_LIMIT.toMinutes()));
        }

        System.out.println();
        System.out.println("Dispatch progress saved to:");
        System.out.println("  " + databaseFile);
        System.out.println("To look inside it:");
        System.out.println("  sqlite3 \"" + databaseFile + "\" \"SELECT * FROM device_command;\"");
    }

    private static void printCommands(DispatchStore store, String mrid) {
        for (StoredCommand entry : store.commandsFor(mrid)) {
            System.out.printf("  %-10s %2d kW  %s%n",
                    entry.command().deviceId(), entry.command().targetKw(), entry.state());
        }
    }

    /** A clock that only moves when the demo moves it. */
    private static final class SimulatedClock extends Clock {

        private Instant now;

        SimulatedClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
