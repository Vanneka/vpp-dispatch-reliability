# Changelog

All notable changes to this project are recorded here. Versions follow [semantic versioning](https://semver.org/); 0.x releases are pre-releases.

## [0.1.0] - 2026-11-09

### Added
- Simulated fleet of devices, with an injectable fault that drops a device's response.
- Device selection: largest available devices first, until the requested power is covered.
- Dispatch coordinator for a single platform instance: saves the full plan in one transaction, then records each command as sent and each response as received.
- Durable dispatch progress in a single SQLite file.
- Scenario tests: all devices confirm, a dropped response, progress read back after reopening the database, a save that fails partway leaving nothing behind, and a request larger than the fleet.
- `./gradlew run` demo: one simulated dispatch with a lost response, saved to `data/demo.db` in the project folder; prints the file's full path.
- README and design notes, including the known gap between sending a command and recording it.
