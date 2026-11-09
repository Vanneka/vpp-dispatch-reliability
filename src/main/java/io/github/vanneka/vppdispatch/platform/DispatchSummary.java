package io.github.vanneka.vppdispatch.platform;

/**
 * What the platform knows about a dispatch after sending it.
 *
 * @param plannedKw   total power the chosen devices were asked to deliver
 * @param confirmedKw power from devices whose response was recorded
 */
public record DispatchSummary(
        String dispatchId,
        int commandsPlanned,
        int commandsSent,
        int responsesRecorded,
        int plannedKw,
        int confirmedKw) {
}
