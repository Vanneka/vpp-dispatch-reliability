package io.github.vanneka.vppdispatch.platform;

import io.github.vanneka.vppdispatch.failover.LeaderElection;
import io.github.vanneka.vppdispatch.failover.NotInChargeException;
import io.github.vanneka.vppdispatch.failover.SimulatedCrash;
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
 * it happens. A dispatch is identified by the utility's event ID (mRID): running an
 * event that is already saved does not plan or send it again.
 *
 * <p>Only the instance in charge (the one holding the lease) dispatches. It checks
 * before planning and again before every send, and stops sending as soon as it no
 * longer holds the lease. Recovering a dispatch after a failover is planned for a later
 * step of v0.2.0; see docs/design.md.
 */
public final class DispatchCoordinator {

    private final SimulatedFleet fleet;
    private final DispatchStore store;
    private final DeviceSelector selector;
    private final LeaderElection leader;
    private final SimulatedCrash crash;

    /** A coordinator for a single instance that is always in charge, with no crash. */
    public DispatchCoordinator(SimulatedFleet fleet, DispatchStore store) {
        this(fleet, store, LeaderElection.alwaysInCharge(), SimulatedCrash.none());
    }

    /**
     * A coordinator that dispatches only while {@code leader} says it is in charge, and
     * that stops at the moment {@code crash} chooses (tests only).
     */
    public DispatchCoordinator(
            SimulatedFleet fleet, DispatchStore store, LeaderElection leader, SimulatedCrash crash) {
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        this.store = Objects.requireNonNull(store, "store");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.crash = Objects.requireNonNull(crash, "crash");
        this.selector = new DeviceSelector();
    }

    public DispatchSummary run(DispatchRequest request) {
        if (!leader.holdsLease()) {
            throw new NotInChargeException(
                    "this instance does not hold the lease; it cannot dispatch " + request.mrid());
        }

        // The same event (same mRID) is never planned twice. Finishing a dispatch that
        // is already saved is the job of recovery, not of a second run.
        if (store.hasDispatch(request.mrid())) {
            return summarize(request.mrid());
        }

        // 1. Choose devices and save the whole plan before sending anything.
        List<DeviceCommand> commands = selector.select(request, fleet.devices());
        store.saveDispatch(request, commands);

        // 2. Send each command and record what happened.
        for (int index = 0; index < commands.size(); index++) {
            // Stop sending as soon as this instance is no longer in charge.
            if (!leader.holdsLease()) {
                break;
            }
            DeviceCommand command = commands.get(index);
            Optional<DeviceResponse> response = fleet.send(command);
            crash.afterSend(index); // tests only: may stop here, before anything is recorded
            store.markSent(command.commandId());
            response.ifPresent(r -> store.markReceived(r.commandId()));
        }

        return summarize(request.mrid());
    }

    /** Builds a summary from what is saved in the store, not from memory. */
    public DispatchSummary summarize(String mrid) {
        List<StoredCommand> stored = store.commandsFor(mrid);
        int sent = 0;
        int received = 0;
        int plannedKw = 0;
        int confirmedKw = 0;
        for (StoredCommand entry : stored) {
            plannedKw += entry.command().targetKw();
            if (entry.state() != CommandState.PLANNED) {
                sent++;
            }
            if (entry.state() == CommandState.RECEIVED) {
                received++;
                confirmedKw += entry.command().targetKw();
            }
        }
        return new DispatchSummary(mrid, stored.size(), sent, received, plannedKw, confirmedKw);
    }
}
