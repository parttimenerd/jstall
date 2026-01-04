# JStall

[![CI](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml)

**JStall answers the age-old question: "What is my Java application doing right now?"**

JStall is a small command-line tool for **one-shot inspection** of running JVMs using thread dumps and short, on-demand profiling.

Features:
* **Deadlock detection** — Find JVM-reported deadlocks quickly
* **Hot thread identification** — See which threads are doing the most work
* **Offline analysis** — Analyze existing thread dumps
* **Flamegraph generation** — Short profiling runs with [async-profiler](https://github.com/async-profiler/async-profiler)
* **Smart filtering** — Target JVMs by name/class instead of PID
* **Multi-execution** — Analyze multiple JVMs in parallel for faster results

## Quick Start

### Installation

Download the latest JAR or executable from the [releases page](https://github.com/parttimenerd/jstall/releases) or
build it from source:

```bash
# Build from source
mvn clean package

# Run (on Linux/Mac)
target/jstall <pid>

# Or run it directly
java -jar target/jstall.jar <pid>
```

### Using JBang

```bash
jbang jstall@parttimenerd/jstall
```

### Basic Usage

```bash
# List all running JVMs
jstall list

# List JVMs with filter
jstall list MyApp

# Inspect a running JVM (default: status command)
jstall 12345
# or: java -jar target/jstall.jar 12345

# Use filter to match JVM by class name (case-insensitive)
jstall status MyApplication
jstall deadlock MyApp

# Analyze multiple JVMs matching a filter in parallel
jstall status DeadlockTestApp

# Check for deadlocks
jstall deadlock 12345

# Find hot threads
jstall most-work 12345 --top 5

# List all threads sorted by CPU time
jstall threads 12345

# List top 10 threads
jstall threads 12345 --top 10

# Analyze offline dumps
jstall status dump1.txt dump2.txt dump3.txt

# Generate flamegraph (quick 10s profile)
jstall flame 12345

# Generate flamegraph with filter (must match exactly one JVM)
jstall flame MyApp

# Generate flamegraph with custom duration and event
jstall flame 12345 -d 30s -e wall
```


## Filtering and Multi-Execution

### Filtering by JVM Name

Instead of specifying a PID, you can use a **filter string** to match JVMs by their main class name (case-insensitive):

```bash
# List matching JVMs
jstall list MyApp

# Analyze JVMs matching the filter
jstall status MyApplication
jstall deadlock kafka
jstall most-work TestApp --top 5
```

**How filtering works:**
1. Checks if target is an existing file → loads as thread dump
2. Checks if target is a numeric PID → uses that JVM
3. Otherwise treats as filter → searches for JVMs with matching main class names

### Multi-Execution

When a filter matches **multiple JVMs** (or you specify multiple targets), analyzer commands will:

* **Analyze all targets in parallel** — Much faster than sequential execution
* **Sort results by PID** — Predictable output order (ascending)
* **Separate results** — Each target's output is clearly separated with dividers

**Example:**
```bash
# If "TestApp" matches PIDs 12345 and 67890
jstall status TestApp

# Output:
# Analysis for PID 12345 (com.example.TestApp):
#
# ... analysis results ...
#
# ================================================================================
#
# Analysis for PID 67890 (com.example.TestApp):
#
# ... analysis results ...
```

**Commands supporting multi-execution:**
* ✅ `status` — Analyzes multiple JVMs in parallel
* ✅ `deadlock` — Checks all matching JVMs for deadlocks
* ✅ `most-work` — Shows hot threads for each JVM
* ✅ `threads` — Lists threads for each JVM
* ❌ `flame` — **Single JVM only** (fails if filter matches multiple)

**Error handling:**
* If a filter matches **no JVMs** → Error with suggestion to run `jstall list`
* If `flame` filter matches **multiple JVMs** → Error listing all matches

## Commands

### `list`

Lists all running JVM processes with optional filtering.

```bash
jstall list [filter]
```

**Arguments:**
* `filter` — Optional filter string to match JVM main class names (case-insensitive)

**Example output:**
```
Available JVM processes:

  12345    com.example.MyApplication
  67890    org.apache.kafka.Kafka
  24680    me.bechberger.jstall.testapp.DeadlockTestApp

Total: 3 JVM(s)
```

**With filter:**
```bash
jstall list kafka

Available JVM processes:

  67890    org.apache.kafka.Kafka

Total: 1 JVM(s) (filtered)
```

**Note:** The list excludes `jps` and the currently running `jstall` process.

---

## Commands

### `status` (default)

Runs multiple analyzers over a shared set of thread dumps.

```bash
jstall [status] <pid | filter | dumps...> [options]
```

**Targets:**
* **PID** — Process ID of a running JVM (e.g., `12345`)
* **Filter** — String to match JVM main class names (case-insensitive, e.g., `MyApp`)
  * If filter matches multiple JVMs, all are analyzed in parallel
  * Results are sorted by PID and separated with dividers
* **Files** — Path to existing thread dump files for offline analysis

**Multi-Execution:**
When a filter matches multiple JVMs, or multiple PIDs/files are provided:
* All targets are analyzed **in parallel** for faster execution
* Results are displayed sorted by PID (ascending order)
* Each result is separated with a divider line (`=` repeated 80 times)

**Analyzers (in order):**
1. `deadlock`
2. `most-work`

**Exit codes:**
* `0` — no deadlock
* `2` — deadlock detected (returns highest exit code from all targets)

**Example:**
```bash
# Analyze all JVMs matching "MyApp"
jstall status MyApp

# Analyze specific PIDs
jstall status 12345 67890
```

---

### `most-work`

Identifies threads doing the most work across multiple dumps.

```bash
jstall most-work <pid | filter | dumps...> [options]
```

**Targets:**
* **PID** — Process ID of a running JVM
* **Filter** — String to match JVM main class names (analyzes all matches in parallel)
* **Files** — Path to existing thread dump files

**Options:**
* `--dumps <n>` — Number of dumps to collect (default: 2, must be ≥ 2)
* `--interval <duration>` — Time between dumps (default: 5s)
* `--top <n>` — Number of top threads to show (default: 3)
* `--keep` — Persist collected dumps to disk
* `--no-native` — Ignore threads without stack traces (typically native/system threads)

**Metrics (when available in thread dumps):**
* **CPU time** — CPU time consumed during the observation period (difference between first and last dump)
* **CPU percentage** — Percentage of total CPU time across all threads
* **Core utilization** — CPU time / elapsed time ratio (shows if thread uses multiple cores)
* **States** — Distribution of thread states across dumps with percentages

**Note:** Threads are grouped by thread ID, so threads with the same name but different IDs are tracked separately.

**Example output:**
```
Top threads by activity (3 dumps):
Combined CPU time: 4.57s, Elapsed time: 10.00s (45.7% overall utilization)

1. Worker-1
   CPU time: 2.45s (53.6% of total)
   Core utilization: 24.5% (~1 core)
   States: RUNNABLE: 100.0%
   Common stack prefix:
     com.example.heavy.Computation.calculate(Computation.java:42)
     com.example.Worker.processTask(Worker.java:78)
```

### `deadlock`

Detects JVM-reported thread deadlocks.

```bash
jstall deadlock <pid | filter | dumps...> [options]
```

**Targets:**
* **PID** — Process ID of a running JVM
* **Filter** — String to match JVM main class names (analyzes all matches in parallel)
* **Files** — Path to existing thread dump files

**Options:**
* `--keep` — Persist collected dumps to disk

**Note:** Uses only the first thread dump. Errors if `--dumps`, `--interval`, or `--top` are passed.

**Exit codes:**
* `0` — no deadlock
* `2` — deadlock detected (returns highest exit code from all targets)

---

### `threads`

Lists all threads sorted by CPU time in a table format.

```bash
jstall threads <pid | filter | dumps...> [options]
```

**Targets:**
* **PID** — Process ID of a running JVM
* **Filter** — String to match JVM main class names (analyzes all matches in parallel)
* **Files** — Path to existing thread dump files

**Options:**
* `--dumps <n>` — Number of dumps to collect (default: 2, must be ≥ 2)
* `--interval <duration>` — Time between dumps (default: 5s)
* `--top <n>` — Maximum number of threads to show (default: all)
* `--keep` — Persist collected dumps to disk
* `--no-native` — Ignore threads without stack traces (typically native/system threads)

**Note:** Threads are grouped by thread ID, so threads with the same name but different IDs are tracked separately.

**Output columns:**
* **THREAD** — Thread name (grouped by thread ID)
* **CPU TIME** — CPU time consumed during the observation period (in seconds)
* **CPU %** — Percentage of total CPU time across all threads
* **STATES** — Distribution of thread states across dumps with percentages
* **TOP STACK FRAME** — Most common top stack frame across all dumps

**Example output:**
```
Threads (3 dumps):
Combined CPU time: 4.57s, Elapsed time: 10.00s (45.7% overall utilization)

THREAD                CPU TIME    CPU %      STATES                          TOP STACK FRAME
------------------------------------------------------------------------------------------------
Worker-1              2.45s       53.6%      RUNNABLE: 100%                  com.example.Worker.processTask
Worker-2              1.23s       26.9%      RUNNABLE: 67%, WAITING: 33%     java.lang.Thread.sleep
GC Thread             0.89s       19.5%      RUNNABLE: 100%                  sun.gc.G1YoungGC.collect
```

---


### `flame`

Generates a flamegraph using async-profiler for CPU, allocation, or lock profiling.

```bash
jstall flame <pid | filter> [options]
```

**Targets:**
* **PID** — Process ID of a running JVM
* **Filter** — String to match JVM main class names
  * **Must match exactly one JVM** — if filter matches multiple JVMs, command fails with error listing all matches
  * Use `jstall list <filter>` to preview which JVMs match

**Options:**
* `-d`, `--duration <duration>` — Profiling duration (default: 10s)
  * Supports: `30s`, `2m`, `500ms`, or bare numbers (treated as seconds)
* `-e`, `--event <event>` — Profiling event (default: cpu)
  * `cpu` — CPU profiling (requires perf_events on Linux)
  * `alloc` — Allocation profiling
  * `lock` — Lock contention profiling
  * `wall` — Wall-clock profiling (works everywhere)
  * `itimer` — Old-style CPU profiling (fallback)
* `-i`, `--interval <interval>` — Sampling interval (default: 10ms)
  * Supports: `10ms`, `1s`, `5000000ns`, `500us`, or bare numbers (treated as nanoseconds)
* `-o`, `--output <file>` — Output file (default: flame.html)
* `--open` — Automatically open the generated HTML file in browser (HTML format only)

**Examples:**

```bash
# Quick CPU profile (10 seconds, default)
jstall flame 12345

# Quick profile and automatically open in browser
jstall flame 12345 --open

# CPU profiling for 30 seconds
jstall flame 12345 -d 30s -e cpu

# Wall-clock profiling with auto-open (works on all platforms)
jstall flame 12345 -e wall -d 60s --open

# Allocation profiling
jstall flame 12345 -e alloc -d 2m -o allocations.html

# Generate JFR file for further analysis
jstall flame 12345 -e cpu -f jfr -d 10s -o profile.jfr

# Lock contention profiling
jstall flame 12345 -e lock -d 15s
```

**Platform Support:**
* Linux (x64, arm64) — Full support for all events
* macOS — Full support (CPU profiling uses wall-clock sampling)

**Note:** Uses [async-profiler](https://github.com/async-profiler/async-profiler) via [ap-loader](https://github.com/jvm-profiling-tools/ap-loader). If async-profiler is not supported on your platform, the command will exit with an error message.

## Development

### Building and Testing

```bash
mvn clean package
```

### Building a native image with GraalVM

```bash
# Requirements: Current JVM must be GraalVM with native-image installed
# Install GraalVM from https://www.graalvm.org/ or use SDKMAN:
# sdk install java 25.0.1-graalce

# Build native image (produces target/jstall binary)
mvn package -DskipTests -Pnative

# Run the native executable
target/jstall <pid>
```

The native image versions are far larger than the normal executable,
but have slightly faster startup times.
Native images are not created by default, as they are platform-specific.

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors