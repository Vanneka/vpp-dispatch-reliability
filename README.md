# vpp-dispatch-reliability

Reference implementation of failover-safe dispatch for virtual power plant (VPP) platforms: recovering in-progress dispatches, rejecting outdated commands, and confirming device responses.

> **Status: v0.1.0, pre-release.** Everything here runs against a **simulated** fleet of devices. It is a reference for VPP platform developers to read, run, and test against. It is not a product, and it makes no claims about real-world performance.

## Why this exists

A VPP spreads its devices across thousands of homes, but in a centralized design every dispatch decision runs through one software platform. Idaho National Laboratory describes these control systems as the "operational 'brain' of a VPP" and "a concentrated digital dependency for grid services" (INL, 2026, §7.2).

Devices and communication links already have well-defined fallback behavior. For example, under the Common Smart Inverter Profile, a device that loses communication finishes its scheduled event and then returns to its default settings (CSIP v2.1, §4.4.2). This repository focuses on the platform's side of the same problem: what the platform should do with a dispatch that is already in progress when one of its own servers restarts. Which devices were told what, which confirmed, and what still needs to be sent?

This repository works through that question in code, one release at a time, alongside a blog series:
- [The Brain of a Virtual Power Plant: Why Its Reliability Matters](https://vanneka-okeke.medium.com/the-brain-of-a-virtual-power-plant-why-its-reliability-matters-7c3855f928ed)

## What v0.1.0 does

A single platform instance runs a dispatch from start to finish and **saves every step to a SQLite file as it happens**:

1. **Choose devices.** Picks devices from a simulated fleet until the requested power is covered.
2. **Save the plan.** Writes the dispatch and every device command to the database in one transaction, before anything is sent.
3. **Send commands.** Sends each command and records it as `SENT`.
4. **Record responses.** Records each device response as `RESPONDED`. A command whose response never arrives stays `SENT`, so the gap is visible.

Because progress lives in the database rather than in memory, a restarted or backup instance can read what was recorded. That record is not yet complete in every case (see the known gap below), and using it to *resume* a dispatch is the next release.

## What it does not do yet

- **No recovery after a restart.** v0.1.0 saves progress; it does not resume from it. There is a known window between sending a command and recording it as sent (see [docs/design.md](docs/design.md)). Closing it is the work of v0.2.0.
- **No primary/backup instances, no rejection of outdated commands, no retries.** See the roadmap below.
- **No real protocols.** Commands are plain Java objects. Later releases shape them after public standards (IEEE 2030.5 / CSIP, OpenADR).

## Before you start

You need **JDK 21** (Java Development Kit, version 21). You do **not** need to install Gradle: the project includes the Gradle wrapper (`gradlew`), which downloads the right Gradle version the first time you run it.

**1. Install JDK 21.** Any JDK 21 distribution works; these commands install Eclipse Temurin:

| System | Command |
|---|---|
| macOS (Homebrew) | `brew install --cask temurin@21` |
| macOS (no Homebrew) | Download the macOS `.pkg` for version 21 from [adoptium.net](https://adoptium.net/temurin/releases/?version=21) (choose *aarch64* for Apple-chip Macs, *x64* for Intel) |
| Windows | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Ubuntu / Debian | `sudo apt install openjdk-21-jdk` |

**2. Check it.** Open a **new** terminal window and run:

```bash
java -version
```

The first line should show version `21`.

**3. Get the code.**

```bash
git clone https://github.com/Vanneka/vpp-dispatch-reliability.git
cd vpp-dispatch-reliability
```

**The first run is slower.** The first `./gradlew` command downloads Gradle and the project's two libraries (SQLite and JUnit), so it needs an internet connection and takes a minute or two. Later runs reuse those downloads.

**On Windows,** use `gradlew.bat` wherever this README says `./gradlew`, for example `gradlew.bat run`.

## Try it

```bash
./gradlew run
```

This runs one simulated dispatch of 12 kW across four batteries, with one battery's response deliberately lost. Output looks like this:

```
Simulated dispatch: 12 kW requested
  battery-1   5 kW  RESPONDED
  battery-2   5 kW  SENT
  battery-3   2 kW  RESPONDED
Planned 12 kW, confirmed 7 kW (2 of 3 responses recorded)

Dispatch progress saved to:
  /path/to/vpp-dispatch-reliability/data/demo.db
```

**Where the database is:** `data/demo.db` inside the project folder. The demo creates the `data/` folder if needed and prints the full path when it finishes, and each run replaces the previous file. To write it somewhere else, pass a path ending in `.db`: `./gradlew run --args="/tmp/my-dispatch.db"`. (The demo refuses other file names, so it can never replace a file that isn't a database.)

To look inside it with the `sqlite3` command-line tool (included with macOS and most Linux distributions):

```bash
sqlite3 data/demo.db "SELECT command_id, target_kw, state FROM device_command;"
```

The `data/` folder is listed in `.gitignore`, so it is never committed. To remove the demo's output, delete the `data/` folder.

## Running the tests

```bash
./gradlew test
```

Each test writes its database to a temporary folder that is deleted when the test finishes, so tests leave no files behind. The scenario tests in `src/test/java` each build a small simulated fleet, run a dispatch, and check what was saved:

| Test | What it checks |
|---|---|
| `everyDeviceConfirms_progressIsSavedToTheDatabase` | All commands end up `RESPONDED`; the last device is asked only for what is still needed. |
| `droppedResponse_commandIsRecordedAsSentButNotConfirmed` | A lost response leaves that command `SENT`, while the device still holds its command. |
| `savedProgressIsStillThereAfterTheDatabaseIsReopened` | A fresh connection, as a restarted instance would open, sees the same progress. |
| `failedSave_leavesNoPartOfThePlanBehind` | If saving the plan fails partway, none of it is kept. |
| `notEnoughCapacity_nothingIsSaved` | If the fleet cannot cover the request, nothing is written. |

## Troubleshooting

| You see | What it means | Fix |
|---|---|---|
| `Unable to locate a Java Runtime` (macOS) or `JAVA_HOME is not set` | No JDK is installed, or the terminal can't find it | Install JDK 21 (see [Before you start](#before-you-start)), then open a new terminal |
| `Cannot find a Java installation on your machine matching …languageVersion=21…` | Java is installed, but not version 21 | Install JDK 21 alongside it. On macOS you can make it the default with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `permission denied: ./gradlew` | The script lost its "executable" setting | Run `chmod +x gradlew` once |
| `sqlite3: command not found` | The SQLite command-line tool isn't installed (common on Windows) | Optional. The demo works without it; install it from [sqlite.org](https://sqlite.org/download.html) only if you want to look inside `data/demo.db` |

## Layout

```
src/main/java/io/github/vanneka/vppdispatch/
  Demo.java   the program behind ./gradlew run
  protocol/   messages: dispatch requests, device commands, device responses
  fleet/      simulated devices, with injectable faults
  storage/    durable dispatch progress (SQLite)
  platform/   device selection and the dispatch coordinator
src/test/java/  scenario tests
docs/design.md  design decisions, the known gap, and sources
data/           created by ./gradlew run; holds demo.db (not committed)
```

## Roadmap

| Release | Adds |
|---|---|
| **v0.1.0** | Simulated fleet; dispatch progress saved to SQLite; scenario tests |
| v0.2.0 | Primary and backup instances; resuming an in-progress dispatch after a restart, so each device ends up with one effective command |
| v0.3.0 | Rejecting outdated or superseded commands |
| v0.4.0 | Reliable device responses: retries, with each response counted once |
| v1.0.0 | Fault-injection test harness and a full write-up |

## Sources

- M. Culler et al., *Virtual Power Plant Architecture and Resilient Design*, INL/RPT-26-89692, Idaho National Laboratory, January 2026. https://inl.gov/content/uploads/2026/01/VPP-Architecture-and-Design-Report__Final.pdf
- Common Smart Inverter Profile Working Group, *Common Smart Inverter Profile: IEEE 2030.5 Implementation Guide for Smart Inverters*, v2.1, March 2018. https://sunspec.org/wp-content/uploads/2025/01/CSIPImplementationGuidev2.103-15-2018.pdf
- G. Zhang, C. Jiang, X. Wang, "Comprehensive review on structure and operation of virtual power plant in electrical system," *IET Generation, Transmission & Distribution* 13(2), 145–156, 2019. https://doi.org/10.1049/iet-gtd.2018.5880

## License
Apache-2.0. See [LICENSE](LICENSE).