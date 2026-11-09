package io.github.vanneka.vppdispatch.platform;

import io.github.vanneka.vppdispatch.fleet.SimulatedFleet;
import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DeviceResponse;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import io.github.vanneka.vppdispatch.storage.CommandState;
import io.github.vanneka.vppdispatch.storage.DispatchStore;
import io.github.vanneka.vppdispatch.storage.StoredCommand;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a dispatch from start to finish on a single platform instance:
 * choose devices, save the plan, send each command, and record each response.
 *
 * <p>Every step is written to the {@link DispatchStore} as it happens, right after
 * it happens. v0.1.0 does not yet recover from a restart, and there is a short window
 * between sending a command and recording it; see docs/design.md for that gap.
 */
public final class DispatchCoordinator {

    private final SimulatedFleet fleet;
    private final DispatchStore store;
    private final DeviceSelector selector;

    public DispatchCoordinator(SimulatedFleet fleet, DispatchStore store) {
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        this.store = Objects.requireNonNull(store, "store");
        this.selector = new DeviceSelector();
    }

    public DispatchSummary run(DispatchRequest request) {
        // 1. Choose devices and save the whole plan before sending anything.
        List<DeviceCommand> commands = selector.select(request, fleet.devices());
        store.saveDispatch(request, commands);

        // 2. Send each command and record what happened.
        for (DeviceCommand command : commands) {
            Optional<DeviceResponse> response = fleet.send(command);
            store.markSent(command.commandId());
            response.ifPresent(r -> store.markResponded(r.commandId()));
        }

        return summarize(request.dispatchId());
    }

    /** Builds a summary from what is saved in the store, not from memory. */
    public DispatchSummary summarize(String dispatchId) {
        List<StoredCommand> stored = store.commandsFor(dispatchId);
        int sent = 0;
        int responded = 0;
        int plannedKw = 0;
        int confirmedKw = 0;
        for (StoredCommand entry : stored) {
            plannedKw += entry.command().targetKw();
            if (entry.state() != CommandState.PLANNED) {
                sent++;
            }
            if (entry.state() == CommandState.RESPONDED) {
                responded++;
                confirmedKw += entry.command().targetKw();
            }
        }
        return new DispatchSummary(dispatchId, stored.size(), sent, responded, plannedKw, confirmedKw);
    }
}
