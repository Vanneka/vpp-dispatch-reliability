package io.github.vanneka.vppdispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.platform.DispatchCoordinator;
import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.SqliteDispatchStore;
import io.github.vanneka.vppdispatch.utility.SimulatedUtilityServer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An event is identified by the utility's mRID, not by its contents. Two different
 * events with the same time and value are both dispatched; the same event delivered
 * twice is not planned or sent again.
 */
class EventIdentityTest {

    private static final Instant CREATED = Instant.parse("2026-07-15T18:00:00Z");
    private static final Instant FIVE_PM = Instant.parse("2026-07-15T21:00:00Z");
    private static final Duration TWO_HOURS = Duration.ofHours(2);

    @TempDir
    Path tempDir;

    private static SimulatedFleet oneBattery() {
        return new SimulatedFleet().add(new SimulatedDevice("battery-1", 5));
    }

    @Test
    void commandIdComesFromTheEventAndTheDevice() {
        DispatchRequest event = new DispatchRequest("EVENT0001", CREATED, 5, FIVE_PM, TWO_HOURS);
        assertEquals("EVENT0001:battery-1", DeviceCommand.forDevice(event, "battery-1", 5).commandId());
    }

    @Test
    void sameEventDeliveredTwice_isNotPlannedOrSentAgain() {
        SimulatedFleet fleet = oneBattery();
        DispatchRequest event = new DispatchRequest("EVENT0001", CREATED, 5, FIVE_PM, TWO_HOURS);

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchCoordinator coordinator = new DispatchCoordinator(fleet, store);
            coordinator.run(event);
            coordinator.run(event);

            assertEquals(1, store.commandsFor("EVENT0001").size());
        }
        assertEquals(1, fleet.device("battery-1").commandsReceived());
    }

    @Test
    void differentEventsWithTheSameTimeAndValue_areBothDispatched() {
        SimulatedFleet fleet = oneBattery();
        DispatchRequest first = new DispatchRequest("EVENT0001", CREATED, 5, FIVE_PM, TWO_HOURS);
        DispatchRequest second = new DispatchRequest("EVENT0002", CREATED, 5, FIVE_PM, TWO_HOURS);

        try (SqliteDispatchStore store = new SqliteDispatchStore(tempDir.resolve("progress.db"))) {
            DispatchCoordinator coordinator = new DispatchCoordinator(fleet, store);
            coordinator.run(first);
            coordinator.run(second);

            assertEquals(1, store.commandsFor("EVENT0001").size());
            assertEquals(1, store.commandsFor("EVENT0002").size());
        }
        assertEquals(2, fleet.device("battery-1").commandsReceived());
    }

    @Test
    void utilityServerKeepsEveryIssuedEventInOrder() {
        SimulatedUtilityServer utility = new SimulatedUtilityServer();
        DispatchRequest first = new DispatchRequest("EVENT0001", CREATED, 5, FIVE_PM, TWO_HOURS);
        DispatchRequest second = new DispatchRequest("EVENT0002", CREATED, 3, FIVE_PM, TWO_HOURS);

        utility.issue(first);
        utility.issue(second);

        assertEquals(2, utility.events().size());
        assertEquals("EVENT0001", utility.events().get(0).mrid());
        assertThrows(IllegalArgumentException.class, () -> utility.issue(first));
    }
}
