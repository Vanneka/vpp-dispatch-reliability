# Design notes

This document explains the decisions behind the reference implementation and what each release changes. Everything described here runs against a simulated fleet.

## The problem in one picture

```
 grid operator / utility
          │  dispatch request (e.g. 50 MW, 5–7 p.m.)
          ▼
 ┌────────────────────┐        ┌──────────────────────┐
 │  VPP platform      │◄──────►│  dispatch progress   │
 │  (the "brain")     │        │  (durable storage)   │
 └────────────────────┘        └──────────────────────┘
          │ commands  ▲ responses
          ▼           │
   device  device  device  …  (thousands)
```

Devices can cover for each other: if one battery is unavailable, another can be asked instead. None of them can cover for the platform. If the platform's server restarts in the middle of a dispatch, whatever replaces it needs to know which devices were already told what, and which confirmed.

## Decisions in v0.1.0

**Save the whole plan before sending anything.** The dispatch and all of its device commands are written in one database transaction. Either the full plan is saved or none of it is. A replacement server therefore never sees half a plan.

**Record each step as it happens.** Every command moves through three recorded states:

| State | Meaning |
|---|---|
| `PLANNED` | The device was chosen and the command saved, but not sent. |
| `SENT` | The command was sent to the device's control unit; no confirmation has been recorded. |
| `RECEIVED` | The control unit confirmed the device has the command (called `RESPONDED` in v0.1.0). This confirms delivery only, not that the event will be carried out. |

These states are internal to the platform. Their names follow the stages CSIP describes for events ("received, started, completed, superseded"; CSIP v2.1, §4.4.1), but they are not IEEE 2030.5 status codes.

A summary of the dispatch is always built from what is in the database, never from what one process happens to remember.

**Stable command IDs.** A command's ID is built from the event and the device, so the same device in the same event always gets the same ID. In v0.2.0 the event part is the utility's mRID (`mrid:deviceId`); see below. If a command or a response is sent twice after a restart, the repeat can be recognized.

**One SQLite file.** It is enough to show the idea, easy to inspect, and needs no database server. Later releases run two platform servers against the same file.

**A deliberately simple device selector.** Largest available devices first, until the target is covered. Real platforms weigh many more factors, such as device state, forecasts, and customer settings (see Zhang et al., 2019, §2.2, on static and dynamic DER parameters). Selection is not what this repository is about.

## In progress for v0.2.0

**Events are identified by the utility's mRID.** The incoming request is modeled on the IEEE 2030.5 DERControl event as shown in CSIP (v2.1, Figure 12): an `mRID`, a `creationTime`, and an interval with an absolute start time and a duration. The requested power is an absolute value. Each device command's ID is `mrid:deviceId`, derived from the event, never from the platform's clock, so any platform server reading the same event gets the same IDs. Two different events with the same time and value are both dispatched; the same event delivered twice is not planned or sent again. This is a plain Java model, not the 2030.5 wire format, and it does not yet handle superseded or cancelled events (v0.3.0).

**Device commands are internal to the platform.** CSIP leaves how an aggregator activates a control on its devices "outside the scope of CSIP" (v2.1, §6.2.4), so device commands are not modeled as a 2030.5 resource.

**States only move forward.** The rule is part of the SQL `UPDATE` (`… WHERE state IN (earlier states)`), so it holds even when two platform servers write to the same file. A late "sent" can't erase a "received," and recording the same step twice does nothing the second time. A confirmation for a command still marked `PLANNED` moves it straight to `RECEIVED`, because the confirmation proves the command arrived.

**A simulated utility server** keeps the list of events it has issued. In CSIP the aggregator can fetch the utility's list of events at any time (v2.1, §6.2.4); recovery after a failover uses it to re-read what the utility asked for.

**Primary and backup, with a lease.** Two platform servers share one SQLite file. A lease, stored as one row (holder and expiry time), decides which server is in charge. The primary renews it every few seconds. If renewals stop, the lease expires after 10 seconds and the backup can take it: a failover. Each check-and-update is a single SQL statement, so two servers cannot both win. The lease sits behind a small `LeaderElection` interface, the point where a real deployment would plug in its own infrastructure (for example, Kubernetes Lease objects, used for "component-level leader election"). The lease is test setup, not the contribution: the contribution is what the backup does after a failover.

The coordinator checks that it holds the lease before planning a dispatch and again before every send, and stops sending as soon as it does not.

**Time is injected.** All lease timing comes from a `java.time.Clock` passed in, so tests move time forward with a manual clock instead of waiting.

**Simulated crash.** A test switch stops the primary right after it sends a command and before it records it as sent, the moment described in the blog's fourth post, or later in the plan to leave some commands recorded and some not.

**Recovery after a failover.** When the backup becomes the server in charge, it calls `recoverAfterFailover`. It has two sources: the utility's list of events (what was asked for), and the platform's own saved record (what each device was told). Only the second exists nowhere else. For each event on the utility's list, in order:

- **Not in the record:** no server planned it, for example because its notification arrived while no server was in charge. It is dispatched now, like a new event.
- **In the record:** every command not yet `RECEIVED` is sent again, in plan order. The record cannot tell a `PLANNED` command that was never sent from one that was sent just before the crash, so both are sent. Commands already `RECEIVED` are not sent again, and the plan is not changed: no device is added or re-chosen.

Sending a command twice is safe because of how the command is built, not because devices are assumed to ignore repeats: it keeps the same ID (`mrid:deviceId`) and carries an absolute value for an absolute time window, so a second copy says exactly what the first said. CSIP does not say how devices treat a repeated command (v2.1, §6.2.4 leaves the aggregator-to-device path out of scope), so how a real device reacts to a repeat is manufacturer-specific. Recovery checks the lease before every send, like a normal dispatch.

**Not covered by recovery in v0.2.0:**
- *Replacement commands.* Moving an unconfirmed device's share to other devices (the blog's fourth post) needs the original command to be marked as replaced; otherwise recovery would send it again along with its replacements. That state (`SUPERSEDED`) arrives in v0.3.0, so replacement commands and their recovery move there too.
- *Events that have already ended* are not skipped, and ending an event (de-activating devices at the end of the window, CSIP v2.1, §6.2.4) is not handled. v0.2.0 recovers the start of an event only.
- *A command whose response never arrives* is sent again on every recovery and stays `SENT`. Retrying with a limit, and flagging commands that never respond, is v0.4.0.

**Known limitation (fixed in v0.3.0).** A primary that is *paused* rather than crashed can wake after its lease has expired and send one command before it checks again. The fix is a leadership number that goes up with every failover, so commands from an older primary are rejected.

**Continuous integration.** GitHub Actions runs the tests on every push and pull request.

## The known gap (planned fix: v0.2.0)

In v0.1.0 the coordinator does this for each device:

```
send command  →  record SENT  →  (if a confirmation arrived) record RECEIVED
```

If the process stops **after sending but before recording SENT**, the database still says `PLANNED`, even though the device has the command. A replacement server reading the database would not know whether to send it again.

Recording `SENT` *before* sending has the opposite problem: if the process stops in between, the database says `SENT` for a command the device never received.

Neither order is safe on its own. v0.2.0 addresses this with primary and backup servers and a way to resume a dispatch in which re-sending a command is harmless, so each device ends up with one effective command. The planned fourth and fifth posts in the blog series cover this.

## What is simulated

- **Devices** hold the last command they receive and reply with a response. They do not model battery physics, state of charge, or timing.
- **Faults** are injected per device. v0.1.0 has one fault: the response is lost, while the device still holds the command.
- **Time** is not simulated. Start times and durations are recorded, not waited for.

## How the code is organized

```
src/main/java/io/github/vanneka/vppdispatch/
  Demo.java    the program behind ./gradlew run
  protocol/    messages: dispatch requests (modeled on DERControl), device commands, device responses
  fleet/       simulated devices, with injectable faults
  storage/     durable dispatch progress (SQLite)
  platform/    device selection and the dispatch coordinator
  failover/    the lease that decides which server is in charge, and the simulated crash
  utility/     a stand-in for the utility's server and its list of events
src/test/java/   scenario tests
docs/            design notes and getting started
data/            created by ./gradlew run; holds demo.db (not committed)
```

## Sources

- INL (2026), §7.2: aggregators' centralized control systems "form the operational 'brain' of a VPP and represent a concentrated digital dependency for grid services." §7.3 recommends "clear rules for how DERs and aggregators should behave when communications are disrupted."
- CSIP v2.1 (2018), §4.4.2: "Should there be a loss of communications, DERs SHALL complete any scheduled event and then revert to default settings…"
- Zhang, Jiang & Wang (2019), §2.2–2.3: static and dynamic DER parameters used to choose devices; centralized control, in which a control coordination centre has "the absolute authority to dispatch all integrated DERs."

Full citations are in the [README](../README.md#sources).
