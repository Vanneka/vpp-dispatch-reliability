package io.github.vanneka.vppdispatch.failover;

/**
 * A switch that makes a platform instance stop dead at a chosen moment, for tests.
 *
 * <p>{@link #afterSendingCommand(int)} stops the instance right after it sends a command
 * and before it records that command as sent: the moment described in the blog series'
 * fourth post. The device has the command, but the record still says PLANNED. Choosing a
 * later command simulates a crash partway through the plan.
 *
 * <p>This is a simulation. A real crash would stop the whole process; here the instance
 * throws {@link SimulatedCrashException} and the test stops using it.
 */
public final class SimulatedCrash {

    private static final int NEVER = -1;

    private final int crashAfterCommandIndex;

    private SimulatedCrash(int crashAfterCommandIndex) {
        this.crashAfterCommandIndex = crashAfterCommandIndex;
    }

    /** No crash. */
    public static SimulatedCrash none() {
        return new SimulatedCrash(NEVER);
    }

    /**
     * Crash right after sending the command at {@code index} in the plan (0 = the first),
     * before recording it as sent.
     */
    public static SimulatedCrash afterSendingCommand(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        return new SimulatedCrash(index);
    }

    /** Called by the coordinator after it sends the command at {@code index}. */
    public void afterSend(int index) {
        if (index == crashAfterCommandIndex) {
            throw new SimulatedCrashException(index);
        }
    }
}
