package io.github.vanneka.vppdispatch.platform;

/**
 * What the platform knows about a dispatch after sending it.
 *
 * @param mrid                  the utility's event ID
 * @param confirmationsRecorded commands whose device confirmed it has them (RECEIVED)
 * @param plannedKw             total power the chosen devices were asked to deliver
 * @param confirmedKw           power from devices whose confirmation was recorded
 */
public record DispatchSummary(
        String mrid,
        int commandsPlanned,
        int commandsSent,
        int confirmationsRecorded,
        int plannedKw,
        int confirmedKw) {
}
