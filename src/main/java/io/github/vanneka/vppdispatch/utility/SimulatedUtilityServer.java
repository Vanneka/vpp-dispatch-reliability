package io.github.vanneka.vppdispatch.utility;

import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A stand-in for the utility's server: it keeps the list of events it has issued.
 *
 * <p>In CSIP, the aggregator learns about new events through notifications and can
 * fetch the utility's list of events (the DERControlList) at any time (CSIP v2.1,
 * §6.2.4). This simulation keeps only that list, so a platform server can always
 * re-read what the utility asked for. It does not model notifications, the network,
 * or the IEEE 2030.5 wire format.
 */
public final class SimulatedUtilityServer {

    private final Map<String, DispatchRequest> events = new LinkedHashMap<>();

    /** Issues an event. Issuing the same mRID twice is an error: each event has its own ID. */
    public void issue(DispatchRequest event) {
        Objects.requireNonNull(event, "event");
        if (events.putIfAbsent(event.mrid(), event) != null) {
            throw new IllegalArgumentException("event " + event.mrid() + " was already issued");
        }
    }

    /** Every event issued so far, in the order it was issued. */
    public List<DispatchRequest> events() {
        return Collections.unmodifiableList(new ArrayList<>(events.values()));
    }
}
