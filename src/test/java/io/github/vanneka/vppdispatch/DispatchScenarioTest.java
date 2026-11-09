package io.github.vanneka.vppdispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.fleet.SimulatedFault;
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.platform.DispatchSummary;
import io.github.vanneka.vppdispatch.platform.InsufficientCapacityException;
import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.CommandState;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.storage.StoreException;
import io.github.vanneka.vppdispatch.storage.StoredCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scenario tests for a single-instance dispatch. Each test builds a small simulated
 * fleet, runs a dispatch, and checks what the platform saved to the database.
 */
class DispatchScenarioTest {

    private static final Instant FIVE_PM = Instant.parse("2026-07-15T21:00:00Z");
    private static final Duration TWO_HOURS = Duration.ofHours(2);

    @TempDir
    Path tempDir;

    private static SimulatedFleet fourBatteries() {
        return new SimulatedFleet()
                .add(new SimulatedDevice("battery-1", 5))
                .add(new SimulatedDevice("battery-2", 5))
                .add(new SimulatedDevice("battery-3", 4))
                .add(new SimulatedDevice("battery-4", 3));
    }

    @Test
    void everyDeviceConfirms_progressIsSavedToTheDatabase() {
        SimulatedFleet fleet = fourBatteries();
        DispatchRequest request = new DispatchRequest("dispatch-1", 12, FIVE_PM, TWO_HOURS);

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchSummary summary = new DispatchCoordinator(fleet, store).run(request);

            // Largest devices first: 5 + 5 + 2 (battery-3 is asked only for what is left).
            assertEquals(3, summary.commandsPlanned());
            assertEquals(3, summary.commandsSent());
            assertEquals(3, summary.responsesRecorded());
            assertEquals(12, summary.plannedKw());
            assertEquals(12, summary.confirmedKw());

            List<StoredCommand> saved = store.commandsFor("dispatch-1");
            assertTrue(saved.stream().allMatch(c -> c.state() == CommandState.RESPONDED));
            assertEquals(2, saved.get(2).command().targetKw());
        }

        // Each chosen device is holding its command for the start time; the unused one is not.
        assertTrue(fleet.device("battery-1").heldCommand().isPresent());
        assertTrue(fleet.device("battery-4").heldCommand().isEmpty());
    }

    @Test
    void droppedResponse_commandIsRecordedAsSentButNotConfirmed() {
        SimulatedFleet fleet = fourBatteries();
        fleet.device("battery-2").injectFault(SimulatedFault.DROP_RESPONSE);
        DispatchRequest request = new DispatchRequest("dispatch-2", 10, FIVE_PM, TWO_HOURS);

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchSummary summary = new DispatchCoordinator(fleet, store).run(request);

            assertEquals(2, summary.commandsSent());
            assertEquals(1, summary.responsesRecorded());
            assertEquals(10, summary.plannedKw());
            assertEquals(5, summary.confirmedKw());

            StoredCommand battery2 = store.commandsFor("dispatch-2").stream()
                    .filter(c -> c.command().deviceId().equals("battery-2"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(CommandState.SENT, battery2.state());
        }

        // The device did receive the command; only its response was lost.
        assertTrue(fleet.device("battery-2").heldCommand().isPresent());
    }

    @Test
    void savedProgressIsStillThereAfterTheDatabaseIsReopened() {
        Path databaseFile = tempDir.resolve("progress.db");
        SimulatedFleet fleet = fourBatteries();
        fleet.device("battery-1").injectFault(SimulatedFault.DROP_RESPONSE);
        DispatchRequest request = new DispatchRequest("dispatch-3", 10, FIVE_PM, TWO_HOURS);

        try (SqliteDispatchStore store = new SqliteDispatchStore(databaseFile)) {
            new DispatchCoordinator(fleet, store).run(request);
        }

        // A fresh connection, as a restarted or backup instance would open.
        try (SqliteDispatchStore reopened = new SqliteDispatchStore(databaseFile)) {
            DispatchSummary summary = new DispatchCoordinator(fleet, reopened).summarize("dispatch-3");

            assertEquals(2, summary.commandsPlanned());
            assertEquals(2, summary.commandsSent());
            assertEquals(1, summary.responsesRecorded());
            assertEquals(5, summary.confirmedKw());
        }
    }

    @Test
    void failedSave_leavesNoPartOfThePlanBehind() {
        DispatchRequest request = new DispatchRequest("dispatch-5", 10, FIVE_PM, TWO_HOURS);
        DeviceCommand first = DeviceCommand.forDevice(request, "battery-1", 5);
        // Same device twice gives the same command ID, so the second insert fails.
        List<DeviceCommand> brokenPlan = List.of(first, first);

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            assertThrows(StoreException.class, () -> store.saveDispatch(request, brokenPlan));
            assertTrue(store.commandsFor("dispatch-5").isEmpty());

            // The dispatch row was rolled back too, so the same dispatch can be saved cleanly.
            store.saveDispatch(request, List.of(first));
            assertEquals(1, store.commandsFor("dispatch-5").size());
        }
    }

    @Test
    void notEnoughCapacity_nothingIsSaved() {
        SimulatedFleet fleet = fourBatteries(); // 17 kW in total
        DispatchRequest request = new DispatchRequest("dispatch-4", 50, FIVE_PM, TWO_HOURS);

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            assertThrows(InsufficientCapacityException.class,
                    () -> new DispatchCoordinator(fleet, store).run(request));
            assertTrue(store.commandsFor("dispatch-4").isEmpty());
        }
    }
}
