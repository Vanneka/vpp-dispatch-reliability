# Changelog

All notable changes to this project are recorded here. Versions follow [semantic versioning](https://semver.org/); 0.x releases are pre-releases.

## [Unreleased]

Recovery after a failover. Everything runs against a simulated fleet.

### Added
- **Recovery after a failover** (`recoverAfterFailover`). The backup re-reads the utility's list of events, dispatches any event not in the record, and sends again every command not yet `RECEIVED`. It does not change the plan.
- **Primary and backup with a lease.** Two servers share one SQLite file, and a lease decides which one is in charge (10 s, renewed about every 3 s), behind a `LeaderElection` interface. The coordinator checks the lease before planning and before every send.
- **CSIP's 15-minute transfer limit** (v2.1, §4.5). The record saves when each event was received and when each command was last sent. `timeToSendAll` and `sentWithin` check a dispatch against `CSIP_TRANSFER_LIMIT` or any other limit, in simulated time.
- **Requests modeled on the IEEE 2030.5 DERControl event** (CSIP v2.1, Figure 12): `mRID`, `creationTime`, absolute start time and duration, and absolute power. This is a plain Java model, not the wire format.
- **Command IDs from the utility's event ID:** `mrid:deviceId`, never taken from the platform's clock.
- **Forward-only states:** `PLANNED` → `SENT` → `RECEIVED`, enforced inside the SQL `UPDATE`.
- **Simulated utility server**, simulated crash, and an injected clock (tests and demo only).
- **Demo:** `./gradlew run` now shows a crash, a failover and the recovery.
- **Tests (30):**
  - Recovery: a crash before recording, a crash partway through the plan, a missed event, recovering twice, a lost response, a server without the lease.
  - Lease and transfer limit.
  - Forward-only states and event identity.
- Tests run on GitHub Actions on every push and pull request.

### Changed
- `RESPONDED` is now `RECEIVED`: the device confirmed delivery, not completion.
- `markSent` and `markReceived` return whether the record moved forward. `saveDispatch` takes the time the event was received.
- An event that is already saved is not planned or sent again.
- Database schema: dispatches are keyed by `mrid` and store `creation_time` and `received_at`, and commands store `last_sent_at`. Databases from v0.1.0 are not compatible.
- README and docs rewritten to be shorter. Setup and troubleshooting moved to `docs/getting-started.md`.

### Fixed
- Removed a duplicate license section from `docs/design.md`.

## [0.1.0] - 2026-11-09

### Added
- Simulated fleet of devices, with an injectable fault that drops a device's response.
- Device selection: largest available devices first, until the requested power is covered.
- Dispatch coordinator for a single platform instance: saves the full plan in one transaction, then records each command as sent and each response as received.
- Durable dispatch progress in a single SQLite file.
- Scenario tests: all devices confirm, a dropped response, progress read back after reopening the database, a save that fails partway leaving nothing behind, and a request larger than the fleet.
- `./gradlew run` demo: one simulated dispatch with a lost response, saved to `data/demo.db` in the project folder; prints the file's full path.
- README and design notes, including the known gap between sending a command and recording it.
