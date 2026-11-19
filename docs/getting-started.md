# Getting started

Everything you need to run the code and look at what it saved. Back to the [README](../README.md).

## 1. Install Java 21

Any JDK 21 works. Gradle installs itself the first time you run `./gradlew`.

| System | Command |
|---|---|
| macOS | `brew install --cask temurin@21` (or the `.pkg` from [adoptium.net](https://adoptium.net/temurin/releases/?version=21)) |
| Windows | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Ubuntu / Debian | `sudo apt install openjdk-21-jdk` |

Check it in a **new** terminal: `java -version` should show `21`.

## 2. Get the code

```bash
git clone https://github.com/Vanneka/vpp-dispatch-reliability.git
cd vpp-dispatch-reliability
```

The first run downloads Gradle, SQLite and JUnit, so it needs internet and takes a minute or two. On Windows, use `gradlew.bat` instead of `./gradlew`.

## 3. Run the tests

```bash
./gradlew test
```

Each test uses a temporary database and leaves no files behind. Test names say what each one checks.

## 4. Run the demo

```bash
./gradlew run
```

Output looks like this:

```
Simulated dispatch DEMO0001: 12 kW requested

Primary stopped after sending to battery-2, before recording it:
  battery-1   5 kW  RECEIVED
  battery-2   5 kW  PLANNED
  battery-3   2 kW  PLANNED

Backup took the lease 11 s later and recovered the dispatch:
  battery-1   5 kW  RECEIVED
  battery-2   5 kW  RECEIVED
  battery-3   2 kW  SENT
Planned 12 kW, confirmed 10 kW (2 of 3 confirmations recorded)
Every command sent 11 s after the event was received (CSIP limit: 15 min)

Dispatch progress saved to:
  /path/to/vpp-dispatch-reliability/data/demo.db
```

What happened:
- The primary sent battery-2 its command and stopped before recording it, so the record still says `PLANNED` even though the device has the command.
- Once the primary's lease expired, the backup took it, sent again every command not yet `RECEIVED`, and left battery-1 alone.
- `battery-3` stays `SENT` because its confirmation was deliberately lost: the record shows exactly what is known.
- Time is simulated, so the demo finishes at once.

**The database** is `data/demo.db`, replaced on each run and never committed. To write it elsewhere: `./gradlew run --args="/tmp/my-dispatch.db"` (the name must end in `.db`).

**Look inside it** with `sqlite3` (included with macOS and most Linux):

```bash
sqlite3 data/demo.db "SELECT command_id, target_kw, state, last_sent_at FROM device_command;"
```

## Troubleshooting

| You see | What it means | Fix |
|---|---|---|
| `Unable to locate a Java Runtime` (macOS) or `JAVA_HOME is not set` | No JDK is installed, or the terminal can't find it | Install JDK 21 (step 1), then open a new terminal |
| `Cannot find a Java installation on your machine matching …languageVersion=21…` | Java is installed, but not version 21 | Install JDK 21 alongside it. On macOS, make it the default with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `permission denied: ./gradlew` | The script lost its "executable" setting | Run `chmod +x gradlew` once |
| `sqlite3: command not found` | The SQLite command-line tool isn't installed (common on Windows) | Optional; the demo works without it. Install from [sqlite.org](https://sqlite.org/download.html) to look inside `data/demo.db` |
