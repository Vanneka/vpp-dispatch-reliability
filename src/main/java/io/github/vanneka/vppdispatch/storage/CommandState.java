package io.github.vanneka.vppdispatch.storage;

/** How far a device command has progressed, as recorded by the platform. */
public enum CommandState {
    /** The platform chose this device and saved the command, but has not sent it yet. */
    PLANNED,
    /** The platform sent the command; no response has been recorded. */
    SENT,
    /** The device's response was received and recorded. */
    RESPONDED
}
