# Getting started

Everything you need to run the code and look at what it saved. Back to the [README](../README.md).

## 1. Install Java 21

You need **JDK 21** (Java Development Kit, version 21). You do **not** need to install Gradle: the project includes the Gradle wrapper (`gradlew`), which downloads the right version the first time you run it. Any JDK 21 distribution works; these commands install Eclipse Temurin:

| System | Command |
|---|---|
| macOS (Homebrew) | `brew install --cask temurin@21` |
| macOS (no Homebrew) | Download the macOS `.pkg` for version 21 from [adoptium.net](https://adoptium.net/temurin/releases/?version=21) (*aarch64* for Apple-chip Macs, *x64* for Intel) |
| Windows | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Ubuntu / Debian | `sudo apt install openjdk-21-jdk` |

Check it in a **new** terminal window: `java -version` should show version `21`.

## 2. Get the code

```bash
git clone https://github.com/Vanneka/vpp-dispatch-reliability.git
cd vpp-dispatch-reliability
```

The first `./gradlew` command downloads Gradle and the project's two libraries (SQLite and JUnit), so it needs an internet connection and takes a minute or two. Later runs reuse the downloads.

**On Windows,** use `gradlew.bat` wherever these instructions say `./gradlew`.

## 3. Run the tests

```bash
./gradlew test
```

Each test uses a temporary database that is deleted afterward, so tests leave no files behind. The test names say what each one checks.

## 4. Run the demo

```bash
./gradlew run
```

Output looks like this:

```
Simulated dispatch DEMO0001: 12 kW requested
  battery-1   5 kW  RECEIVED
  battery-2   5 kW  SENT
  battery-3   2 kW  RECEIVED
Planned 12 kW, confirmed 7 kW (2 of 3 confirmations recorded)

Dispatch progress saved to:
  /path/to/vpp-dispatch-reliability/data/demo.db
```

`battery-2` stays `SENT` because its confirmation was deliberately lost: the record shows exactly what is known.

**Where the database goes:** `data/demo.db` in the project folder. Each run replaces it. To write it elsewhere, pass a path ending in `.db`: `./gradlew run --args="/tmp/my-dispatch.db"` (other file names are refused, so the demo can never replace a file that isn't a database). The `data/` folder is never committed; delete it to remove the demo's output.

**Look inside it** with the `sqlite3` tool (included with macOS and most Linux distributions):

```bash
sqlite3 data/demo.db "SELECT command_id, target_kw, state FROM device_command;"
```

## Troubleshooting

| You see | What it means | Fix |
|---|---|---|
| `Unable to locate a Java Runtime` (macOS) or `JAVA_HOME is not set` | No JDK is installed, or the terminal can't find it | Install JDK 21 (step 1), then open a new terminal |
| `Cannot find a Java installation on your machine matching …languageVersion=21…` | Java is installed, but not version 21 | Install JDK 21 alongside it. On macOS, make it the default with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `permission denied: ./gradlew` | The script lost its "executable" setting | Run `chmod +x gradlew` once |
| `sqlite3: command not found` | The SQLite command-line tool isn't installed (common on Windows) | Optional; the demo works without it. Install from [sqlite.org](https://sqlite.org/download.html) to look inside `data/demo.db` |
