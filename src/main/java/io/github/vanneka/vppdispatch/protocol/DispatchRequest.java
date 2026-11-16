package io.github.vanneka.vppdispatch.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A request from the utility for the VPP to deliver power: "deliver targetKw from
 * startTime for duration."
 *
 * <p>The fields are modeled on the IEEE 2030.5 DERControl event as shown in the
 * Common Smart Inverter Profile (CSIP v2.1, Figure 12): an {@code mRID} that identifies
 * the event, a {@code creationTime}, and an interval with a start time and a duration.
 * The requested power is an absolute value ("deliver 5 kW"), never a change ("add 2 kW"),
 * and the start is an absolute time, never "in 5 minutes," so the same request means
 * the same thing however many times it is read.
 *
 * <p>This is a plain Java model, not the 2030.5 wire format, and it does not yet follow
 * DERControl's rules for superseding or cancelling events (planned for v0.3.0).
 *
 * @param mrid         the utility's identifier for this event; also the ID of the dispatch
 * @param creationTime when the utility created the event
 */
public record DispatchRequest(
        String mrid,
        Instant creationTime,
        int targetKw,
        Instant startTime,
        Duration duration) {

    public DispatchRequest {
        Objects.requireNonNull(mrid, "mrid");
        Objects.requireNonNull(creationTime, "creationTime");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(duration, "duration");
        if (mrid.isBlank()) {
            throw new IllegalArgumentException("mrid must not be blank");
        }
        if (targetKw <= 0) {
            throw new IllegalArgumentException("targetKw must be positive");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
