package io.github.vanneka.vppdispatch.failover;

/**
 * Decides which platform instance is in charge of dispatching.
 *
 * <p>Only one instance (the primary) may send commands at a time. If it stops, another
 * instance (the backup) takes its place: a failover. Real platforms usually get this
 * from their infrastructure (for example, Kubernetes uses Lease objects for
 * "component-level leader election"). This project uses a simple lease in SQLite as a
 * stand-in, so the recovery logic that comes after a failover can be shown and tested.
 */
public interface LeaderElection {

    /**
     * Tries to become, or stay, the instance in charge. A primary calls this regularly
     * to renew its lease; this renewal is its heartbeat.
     *
     * @return {@code true} if this instance holds the lease afterward
     */
    boolean tryAcquireOrRenew();

    /** Whether this instance holds an unexpired lease right now. */
    boolean holdsLease();

    /** An election that always says this instance is in charge, for single-instance use. */
    static LeaderElection alwaysInCharge() {
        return new LeaderElection() {
            @Override
            public boolean tryAcquireOrRenew() {
                return true;
            }

            @Override
            public boolean holdsLease() {
                return true;
            }
        };
    }
}
