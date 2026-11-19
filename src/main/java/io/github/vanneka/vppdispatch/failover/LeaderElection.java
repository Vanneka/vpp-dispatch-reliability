package io.github.vanneka.vppdispatch.failover;

/**
 * Decides which server is in charge of dispatching.
 *
 * <p>Only one server (the primary) sends commands at a time. If it stops, the backup
 * becomes the primary: a failover. Real platforms usually get this from their
 * infrastructure (for example, Kubernetes Lease objects for "component-level leader
 * election"). Here a simple lease in SQLite stands in for it.
 */
public interface LeaderElection {

    /**
     * Tries to become, or stay, the server in charge. The primary calls this every few
     * seconds to renew its lease.
     *
     * @return {@code true} if this server holds the lease afterward
     */
    boolean tryAcquireOrRenew();

    /** Whether this server holds an unexpired lease right now. */
    boolean holdsLease();

    /** An election that always says this server is in charge, for single-server use. */
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
