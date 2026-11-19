package io.github.vanneka.vppdispatch.failover;

/**
 * A test switch that stops a server at a chosen moment.
 *
 * <p>{@link #afterSendingCommand(int)} stops it right after it sends a command and before
 * it records it as sent (the moment from the blog's fourth post): the device has the
 * command, but the record still says PLANNED. The index is the command's position in the
 * plan, so a later index stops it partway through. The backup always uses {@link #none()}.
 *
 * <p>A real crash stops the whole process. Here the server throws
 * {@link SimulatedCrashException} and the test stops using it.
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
