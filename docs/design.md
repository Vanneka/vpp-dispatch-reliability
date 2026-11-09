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

**Save the whole plan before sending anything.** The dispatch and all of its device commands are written in one database transaction. Either the full plan is saved or none of it is. A replacement instance therefore never sees half a plan.

**Record each step as it happens.** Every command moves through three recorded states:

| State | Meaning |
|---|---|
| `PLANNED` | The device was chosen and the command saved, but not sent. |
| `SENT` | The command was sent; no response has been recorded. |
| `RESPONDED` | The device's response was received and recorded. |

A summary of the dispatch is always built from what is in the database, never from what one process happens to remember.

**Stable command IDs.** A command's ID is `dispatchId:deviceId`, so the same device in the same dispatch always gets the same ID. This costs nothing now and matters in v0.2.0 and v0.4.0: if a command or a response is sent twice after a restart, the repeat can be recognized.

**One SQLite file.** It is enough to show the idea, easy to inspect, and needs no server. Later releases run two platform instances against the same file.

**A deliberately simple device selector.** Largest available devices first, until the target is covered. Real platforms weigh many more factors, such as device state, forecasts, and customer settings (see Zhang et al., 2019, §2.2, on static and dynamic DER parameters). Selection is not what this repository is about.

## The known gap (closed in v0.2.0)

In v0.1.0 the coordinator does this for each device:

```
send command  →  record SENT  →  (if a response arrived) record RESPONDED
```

If the process stops **after sending but before recording SENT**, the database still says `PLANNED`, even though the device has the command. A replacement instance reading the database would not know whether to send it again.

Recording `SENT` *before* sending has the opposite problem: if the process stops in between, the database says `SENT` for a command the device never received.

Neither order is safe on its own. v0.2.0 addresses this with primary and backup instances and a way to resume a dispatch in which re-sending a command is harmless, so each device ends up with one effective command. The planned fourth and fifth posts in the blog series cover this.

## What is simulated

- **Devices** hold the last command they receive and reply with a response. They do not model battery physics, state of charge, or timing.
- **Faults** are injected per device. v0.1.0 has one fault: the response is lost, while the device still holds the command.
- **Time** is not simulated. Start times and durations are recorded, not waited for.

## Sources

- INL (2026), §7.2: aggregators' centralized control systems "form the operational 'brain' of a VPP and represent a concentrated digital dependency for grid services." §7.3 recommends "clear rules for how DERs and aggregators should behave when communications are disrupted."
- CSIP v2.1 (2018), §4.4.2: "Should there be a loss of communications, DERs SHALL complete any scheduled event and then revert to default settings…"
- Zhang, Jiang & Wang (2019), §2.2–2.3: static and dynamic DER parameters used to choose devices; centralized control, in which a control coordination centre has "the absolute authority to dispatch all integrated DERs."

Full citations are in the [README](../README.md#sources).

  ## License

  Apache-2.0. See [LICENSE](LICENSE).