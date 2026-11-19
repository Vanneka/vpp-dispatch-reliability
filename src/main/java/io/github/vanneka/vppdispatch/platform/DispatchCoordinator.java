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
import io.github.vanneka.vppdispatch.utility.SimulatedUtilityServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a dispatch: choose devices, save the plan, send each command, and record each
 * response. Every step is saved as it happens.
 *
 * <p>Only the server holding the lease dispatches. It checks before planning and
 * before every send.
 *
 * <p>After a failover, the backup calls {@link #recoverAfterFailover} to finish what the
 * primary started. Sending a command again is safe: it has the same ID, the same
 * absolute value and the same time window.
 */
public final class DispatchCoordinator {

    /**
     * CSIP's limit for an aggregator to pass a control on to its devices: "within 15
     * minutes of receiving the control from the server" (CSIP v2.1, §4.5).
     */
    public static final Duration CSIP_TRANSFER_LIMIT = Duration.ofMinutes(15);

    private final SimulatedFleet fleet;
    private final DispatchStore store;
    private final DeviceSelector selector;
    private final LeaderElection leader;
    private final SimulatedCrash crash;
    private final Clock clock;

    /** A coordinator for a single server that is always in charge, with no crash. */
    public DispatchCoordinator(SimulatedFleet fleet, DispatchStore store) {
        this(fleet, store, LeaderElection.alwaysInCharge(), SimulatedCrash.none(), Clock.systemUTC());
    }

    /** A coordinator that dispatches only while it holds the lease; {@code crash} is for tests. */
    public DispatchCoordinator(
            SimulatedFleet fleet, DispatchStore store, LeaderElection leader, SimulatedCrash crash) {
        this(fleet, store, leader, crash, Clock.systemUTC());
    }

    /** As above, with times (received, sent) taken from {@code clock}; tests pass a manual clock. */
    public DispatchCoordinator(
            SimulatedFleet fleet, DispatchStore store, LeaderElection leader, SimulatedCrash crash,
            Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        this.store = Objects.requireNonNull(store, "store");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.crash = Objects.requireNonNull(crash, "crash");
        this.selector = new DeviceSelector();
    }

    public DispatchSummary run(DispatchRequest request) {
        if (!leader.holdsLease()) {
            throw new NotInChargeException(
                    "this server does not hold the lease; it cannot dispatch " + request.mrid());
        }

        // An event that is already saved is not planned again; recoverAfterFailover finishes it.
        if (store.hasDispatch(request.mrid())) {
            return summarize(request.mrid());
        }

        // 1. Choose devices and save the whole plan before sending anything.
        List<DeviceCommand> commands = selector.select(request, fleet.devices());
        store.saveDispatch(request, commands, clock.instant());

        // 2. Send each command and record what happened.
        sendInOrder(commands);

        return summarize(request.mrid());
    }

    /**
     * Recovery after a failover. For each event the utility has issued:
     * <ul>
     *   <li>Not saved yet: dispatch it now.</li>
     *   <li>Saved: send again every command not yet {@code RECEIVED}.</li>
     * </ul>
     *
     * <p>The plan is not changed, and sending stops if this server loses the lease.
     *
     * @return a summary for each event handled, built from the saved record
     * @throws NotInChargeException if this server does not hold the lease
     */
    public List<DispatchSummary> recoverAfterFailover(SimulatedUtilityServer utility) {
        Objects.requireNonNull(utility, "utility");
        if (!leader.holdsLease()) {
            throw new NotInChargeException(
                    "this server does not hold the lease; it cannot recover dispatches");
        }

        List<DispatchSummary> summaries = new ArrayList<>();
        for (DispatchRequest event : utility.events()) {
            if (!leader.holdsLease()) {
                break;
            }
            if (!store.hasDispatch(event.mrid())) {
                summaries.add(run(event));
                continue;
            }
            List<DeviceCommand> notYetReceived = new ArrayList<>();
            for (StoredCommand entry : store.commandsFor(event.mrid())) {
                if (entry.state() != CommandState.RECEIVED) {
                    notYetReceived.add(entry.command());
                }
            }
            sendInOrder(notYetReceived);
            summaries.add(summarize(event.mrid()));
        }
        return summaries;
    }

    /**
     * Sends each command in order. Records SENT after sending, and RECEIVED if the
     * device responds. Stops if this server (the primary or the backup running this
     * coordinator) loses the lease.
     */
    private void sendInOrder(List<DeviceCommand> commands) {
        for (int index = 0; index < commands.size(); index++) {
            if (!leader.holdsLease()) {
                break;
            }
            DeviceCommand command = commands.get(index);
            Optional<DeviceResponse> response = fleet.send(command);
            crash.afterSend(index); // tests only: may stop here, before anything is recorded
            store.markSent(command.commandId(), clock.instant());
            response.ifPresent(r -> store.markReceived(r.commandId()));
        }
    }

    /**
     * How long it took, from receiving the event, until every command in the plan had
     * been sent; empty if any command has never been recorded as sent. Uses the latest
     * recorded send of each command.
     */
    public Optional<Duration> timeToSendAll(String mrid) {
        Instant receivedAt = store.receivedAt(mrid);
        Instant lastSend = receivedAt;
        for (StoredCommand entry : store.commandsFor(mrid)) {
            Optional<Instant> sentAt = store.lastSentAt(entry.command().commandId());
            if (sentAt.isEmpty()) {
                return Optional.empty();
            }
            if (sentAt.get().isAfter(lastSend)) {
                lastSend = sentAt.get();
            }
        }
        return Optional.of(Duration.between(receivedAt, lastSend));
    }

    /** Whether every command in the plan was sent within {@code limit} of receiving the event. */
    public boolean sentWithin(String mrid, Duration limit) {
        return timeToSendAll(mrid).map(time -> time.compareTo(limit) <= 0).orElse(false);
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
