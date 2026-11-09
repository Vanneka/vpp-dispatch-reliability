package io.github.vanneka.vppdispatch.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A request for the VPP to deliver power, for example an accepted market
 * offer or a utility program event: "deliver targetKw from startTime for duration."
 */
public record DispatchRequest(String dispatchId, int targetKw, Instant startTime, Duration duration) {

    public DispatchRequest {
        Objects.requireNonNull(dispatchId, "dispatchId");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(duration, "duration");
        if (targetKw <= 0) {
            throw new IllegalArgumentException("targetKw must be positive");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
