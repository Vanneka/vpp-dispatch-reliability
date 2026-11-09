package io.github.vanneka.vppdispatch;

import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.fleet.SimulatedFault;
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.platform.DispatchSummary;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.storage.StoredCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Runs one simulated dispatch and saves its progress to a SQLite file you can open.
 *
 * <p>Run with {@code ./gradlew run}. The database is written to {@code data/demo.db}
 * under the folder you run it from (the project folder, when run through Gradle), or to
 * the path given as the first argument, which must end in {@code .db}. Missing folders
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
        // One device's response is lost on the way back, so the output shows a command
        // that was sent but never confirmed.
        fleet.device("battery-2").injectFault(SimulatedFault.DROP_RESPONSE);

        DispatchRequest request = new DispatchRequest(
                "demo-dispatch", 12, Instant.parse("2026-07-15T21:00:00Z"), Duration.ofHours(2));

        try (SqliteDispatchStore store = new SqliteDispatchStore(databaseFile)) {
            DispatchSummary summary = new DispatchCoordinator(fleet, store).run(request);

            System.out.println("Simulated dispatch: " + request.targetKw() + " kW requested");
            for (StoredCommand entry : store.commandsFor(request.dispatchId())) {
                System.out.printf("  %-10s %2d kW  %s%n",
                        entry.command().deviceId(), entry.command().targetKw(), entry.state());
            }
            System.out.printf("Planned %d kW, confirmed %d kW (%d of %d responses recorded)%n",
                    summary.plannedKw(), summary.confirmedKw(),
                    summary.responsesRecorded(), summary.commandsPlanned());
        }

        System.out.println();
        System.out.println("Dispatch progress saved to:");
        System.out.println("  " + databaseFile);
        System.out.println("To look inside it:");
        System.out.println("  sqlite3 \"" + databaseFile + "\" \"SELECT * FROM device_command;\"");
    }
}
