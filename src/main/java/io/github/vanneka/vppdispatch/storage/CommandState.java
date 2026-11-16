package io.github.vanneka.vppdispatch.storage;

import java.util.Arrays;
import java.util.List;

/**
 * How far a device command has progressed, as recorded by the platform.
 *
 * <p>The states are declared in order, and a recorded state only ever moves forward.
 * They are internal to the platform. Their names follow the stages CSIP describes for
 * DERControl events ("received, started, completed, superseded"; CSIP v2.1, §4.4.1),
 * but they are not IEEE 2030.5 status codes. Later releases add more stages.
 */
public enum CommandState {
    /** The platform chose this device and saved the command, but has not sent it yet. */
    PLANNED,
    /** The platform sent the command to the device's control unit; no confirmation is recorded. */
    SENT,
    /**
     * The device's control unit confirmed the device has the command. This confirms
     * delivery only, not that the event will be carried out.
     */
    RECEIVED;

    /** The states that come before this one, from which a command may move to it. */
    public List<CommandState> earlierStates() {
        return Arrays.asList(values()).subList(0, ordinal());
    }
}
