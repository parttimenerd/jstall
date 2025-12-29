# JStall

[![CI](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml)

**JStall answers the age-old question: "What is my Java application doing right now?"**

JStall is a small command-line tool for **one-shot inspection** of running JVMs using thread dumps and short, on-demand profiling.

Features:
* **Deadlock detection** — Find JVM-reported deadlocks quickly
* **Hot thread identification** — See which threads are doing the most work
* **Offline analysis** — Analyze existing thread dumps
* **Flamegraph generation** — Short profiling runs with [async-profiler](https://github.com/async-profiler/async-profiler)

## Quick Start

### Installation

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
# Inspect a running JVM (default: status command)
jstall 12345
# or: java -jar target/jstall.jar 12345

# Check for deadlocks
jstall dead-lock 12345

# Find hot threads
jstall most-work 12345 --top 5

# Analyze offline dumps
jstall status dump1.txt dump2.txt dump3.txt

# Generate flamegraph (quick 10s profile)
jstall flame 12345

# Generate flamegraph with custom duration and event
jstall flame 12345 -d 30s -e wall
```


## Commands

### `status` (default)

Runs multiple analyzers over a shared set of thread dumps.

```bash
jstall [status] <pid | dumps...> [options]
```

**Analyzers (in order):**
1. `dead-lock`
2. `most-work`

**Exit codes:**
* `0` — no deadlock
* `2` — deadlock detected

---

### `most-work`

Identifies threads doing the most work across multiple dumps.

```bash
jstall most-work <pid | dumps...> [options]
```

**Options:**
* `--dumps <n>` — Number of dumps to collect (default: 2, must be ≥ 2)
* `--interval <duration>` — Time between dumps (default: 5s)
* `--top <n>` — Number of top threads to show (default: 3)
* `--keep` — Persist collected dumps to disk
* `--json` — Output as JSON
* `--no-native` — Ignore threads without stack traces (typically native/system threads)

**Metrics (when available in thread dumps):**
* **CPU time** — Total CPU time consumed by the thread (in seconds)
* **CPU percentage** — Percentage of total CPU time across all threads
* **Core utilization** — CPU time / elapsed time ratio (shows if thread uses multiple cores)
* **States** — Distribution of thread states across dumps with percentages

**Example output:**
```
Top threads by activity (3 dumps):

1. Worker-1
   CPU time: 2.45s (45.2% of total)
   Core utilization: 122.5% (~2 cores)
   States: RUNNABLE: 100.0%
   Common stack prefix:
     at com.example.heavy.Computation.calculate(Computation.java:42)
     at com.example.Worker.processTask(Worker.java:78)
```

### `dead-lock`

Detects JVM-reported thread deadlocks.

```bash
jstall dead-lock <pid | dumps...> [options]
```

**Options:**
* `--keep` — Persist collected dumps to disk
* `--json` — Output as JSON

**Note:** Uses only the first thread dump. Errors if `--dumps`, `--interval`, or `--top` are passed.

**Exit codes:**
* `0` — no deadlock
* `2` — deadlock detected

### `flame`

Generates a flamegraph using async-profiler for CPU, allocation, or lock profiling.

```bash
jstall flame <pid> [options]
```

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

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall/issues) issues.
Contribution and feedback are encouraged and always welcome.
For more information about how to contribute, the project structure,
as well as additional contribution information, see our Contribution Guidelines.

## License

MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors