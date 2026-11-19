# Changelog

All notable changes to this project are recorded here. Versions follow [semantic versioning](https://semver.org/); 0.x releases are pre-releases.

## [Unreleased]

### Added
- Tests run automatically on GitHub (GitHub Actions) on every push and pull request.
- Incoming requests modeled on the IEEE 2030.5 DERControl event (as shown in CSIP v2.1, Figure 12): `mRID`, `creationTime`, absolute start time and duration, absolute requested power. A plain Java model, not the wire format.
- Device command IDs built from the utility's event ID: `mrid:deviceId`, never from the platform's clock.
- Forward-only command states: a recorded state only moves from `PLANNED` to `SENT` to `RECEIVED`, never back, enforced inside the SQL `UPDATE`.
- A simulated utility server that keeps the list of issued events.
- Tests: forward-only states, and event identity (the same event twice is not sent again; two different events with the same time and value are both dispatched).
- Primary and backup platform servers sharing one SQLite file, with a lease (10 s, renewed about every 3 s) deciding which one is in charge, behind a `LeaderElection` interface. The coordinator dispatches only while it holds the lease.
- Injected clock for all lease timing, so tests move time forward without waiting.
- A simulated crash that stops the primary right after sending a command and before recording it (tests only).
- Tests: the backup becomes the primary only after the lease expires; an server without the lease cannot dispatch; a crash between sending and recording leaves the record at `PLANNED` while the device holds the command.
- Recovery after a failover (`DispatchCoordinator.recoverAfterFailover`): the new server in charge re-reads the utility's list of events, dispatches any event not in the record (for example, one issued while no server was in charge), and sends again every saved command not yet `RECEIVED`, in plan order, without changing the plan.
- Tests: a crash between sending and recording (the backup sends again and each device ends with the planned command); a crash partway through the plan (the backup finishes it and sends nothing outside it); an event issued while no server was in charge; recovering twice sends nothing more; a lost response is sent again but stays `SENT`; an server without the lease cannot recover.
- README note: "Simulation, not a deployment."
- Both database connections wait briefly for each other's writes (`busy_timeout`) instead of failing at once.

### Changed
- `RESPONDED` is now `RECEIVED`: the device's control unit confirmed the device has the command (delivery, not completion).
- `DispatchStore.markSent` and `markReceived` (was `markResponded`) return whether the record moved forward; an unknown command ID is still an error.
- The coordinator does not plan or send an event that is already saved.
- README shortened to a quick overview, quick start and release table; installation, demo details and troubleshooting moved to `docs/getting-started.md`; code layout moved to `docs/design.md`. Added a test-status badge.
- The database schema identifies dispatches by `mrid` and stores `creation_time`. Databases created by v0.1.0 are not compatible; the demo creates a new file on every run.

### Fixed
- Removed a duplicate license section from the end of `docs/design.md` (its link did not resolve from the `docs/` folder; the README has the license section).

## [0.1.0] - 2026-11-09

### Added
- Simulated fleet of devices, with an injectable fault that drops a device's response.
- Device selection: largest available devices first, until the requested power is covered.
- Dispatch coordinator for a single platform instance: saves the full plan in one transaction, then records each command as sent and each response as received.
- Durable dispatch progress in a single SQLite file.
- Scenario tests: all devices confirm, a dropped response, progress read back after reopening the database, a save that fails partway leaving nothing behind, and a request larger than the fleet.
- `./gradlew run` demo: one simulated dispatch with a lost response, saved to `data/demo.db` in the project folder; prints the file's full path.
- README and design notes, including the known gap between sending a command and recording it.
