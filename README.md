# JStall

[![CI](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/jstall)](https://central.sonatype.com/artifact/me.bechberger/jstall) [![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/30667-jstall.svg)](https://plugins.jetbrains.com/plugin/30667-jstall)

**JStall answers the age-old question: "What is my Java application doing right now?"**

JStall is a small command-line tool for **one-shot inspection** of running JVMs using thread dumps and short, on-demand profiling.

Features:
* **Deadlock detection**: Find JVM-reported deadlocks quickly
* **Hot thread identification**: See which threads are doing the most work
* **Thread activity categorization**: Automatically classify threads by activity (I/O, Network, Database, etc.)
* **Dependency graph**: Visualize which threads wait on locks held by others
* **Starvation detection**: Find threads waiting on the same lock with no progress
* **Intelligent stack filtering**: Collapse framework internals, focus on application code
* **Offline analysis**: Analyze existing thread dumps
* **Flamegraph generation**: Short profiling runs with [async-profiler](https://github.com/async-profiler/async-profiler)
* **Smart filtering**: Target JVMs by name/class instead of PID
* **Multi-execution**: Analyze multiple JVMs in parallel for faster results
* **JVM support checks**: Warn if the target JVM is likely out of support (based on `java.version.date` from `jcmd VM.system_properties`)
* **Supports Java 11+**: Works with all modern Java versions as a target, but requires Java 17+ to run
* **Record & Replay**: Record diagnostic data for later analysis or sharing as a zip file
* **Minimal Builds Available**: There are minimal builds (< 250 KB) available that exclude the bundled async-profiler ([SapMachine](https://sap.github.io/SapMachine/) ships with it anyway) and are optimized via [femtojar](https://github.com/parttimenerd/femtojar).
* **Remote JVMs**: via `-s "ssh user@host" COMMAND` or via `--cf APP COMMAND` for cloud-foundry
* **Live Mode**: Interactive TUI with `--live` for continuous monitoring with sorting, filtering, and color
* **Top threads filtering**: Use `--top=<n>` to show only the top N threads by CPU time (e.g., `jstall threads --top=5 --live <pid>`)

Requires Java 17+ to run.

You can use the `jstall` CLI tool directly, or through the
[IntelliJ plugin](https://plugins.jetbrains.com/plugin/30667-jstall) or
[VSCode extension](https://marketplace.visualstudio.com/items?itemName=bechberger.jstall) (with MCP support).

## Installation

Download the latest executable from the [releases page](https://github.com/parttimenerd/jstall/releases).

Or use with [JBang](https://www.jbang.dev/): `jbang jstall@parttimenerd/jstall <pid>`

## Quick Start

**Example:** Find out what your application (in our example `MyApplication` with pid `12345`) is doing right now

```bash
# Quick status check (checks for deadlocks and hot threads)
jstall 12345

# Or explicitly run the status command, that also supports using JVM name filters
jstall status MyApplication

# Find threads consuming most CPU
jstall most-work 12345

# Monitor all threads in live mode
jstall threads --live 12345

# Monitor top 5 threads in live mode
jstall threads --top=5 --live 12345

# Detect threads stuck waiting on locks
jstall waiting-threads 12345

# Show thread dependency graph (which threads wait on which)
jstall dependency-tree 12345

# Generate a flamegraph
jstall flame 12345

# Record diagnostic data for later analysis or sharing
jstall record 12345 --output myapp-diagnostics.zip

# Run status analysis on the recorded data
jstall -f myapp-diagnostics.zip status all

# Which is equivalent to
jstall status myapp-diagnostics.zip
```

All analysis commands also support the special target `all` to analyze every discovered JVM (or every recorded JVM in replay mode): `jstall status all` or `jstall -f myapp-diagnostics.zip status all`.

### Global Options

| Option | Description |
|--------|----------|
| `-f, --file=<zip>` | Replay mode from a recording ZIP |
| `-s, --ssh=<prefix>` | Run commands on remote host via SSH (e.g., `ssh user@host`) |
| `--cf=<app>` | Cloud Foundry remote execution (shortcut for `--ssh 'cf ssh <app> -c'`) |
| `-v, --verbose` | Verbose logging of remote commands |

### Filtering and Multi-Execution

Use **filter strings** to match JVMs by main class name instead of PIDs:

```bash
jstall list MyApp              # List matching JVMs
jstall status MyApplication    # Analyze matching JVMs
jstall deadlock kafka          # Check deadlocks in matching JVMs
```

**How it works:** Filter strings match main class names (case-insensitive). When multiple JVMs match, they're analyzed **in parallel** with results sorted by PID.

**Note:** `flame` requires exactly one JVM (fails if filter matches multiple).

## Commands

For full command reference with all options, see [docs/COMMANDS.md](docs/COMMANDS.md).

| Command | Description | Key Options |
|---------|-------------|-------------|
| `status` | Run multiple analyzers (default command) | `--top=<n>`, `--intelligent-filter`, `--full`, `--no-native` |
| `deadlock` | Detect JVM-reported thread deadlocks | |
| `most-work` | Identify threads doing the most work | `--top=<n>`, `--stack-depth=<n>`, `--intelligent-filter` |
| `threads` | List all threads sorted by CPU time | `--no-native` |
| `waiting-threads` | Identify threads waiting without progress | `--stack-depth=<n>`, `--intelligent-filter` |
| `dependency-graph` | Show thread lock dependencies | |
| `dependency-tree` | Show dependencies over time | |
| `flame` | Generate a flamegraph via async-profiler | `--duration=<t>`, `--event=<e>`, `--open` |
| `compiler-queue` | Analyze JIT compiler queue state | |
| `record` | Record diagnostic data into a zip | |
| `list` | List running JVM processes | `--no-truncate` |
| `processes` | Detect high-CPU non-JVM processes | |
| `jvm-support` | Check if JVM version is still supported | |
| `vm-vitals` | Show VM.vitals (SapMachine) | |
| `gc-heap-info` | Show GC heap info and change | |
| `vm-classloader-stats` | Show classloader stats | |
| `vm-metaspace` | Show metaspace summary and trend | |

**Common options** (available on most commands):
- `--dump-count=<n>` / `--interval=<t>` — Control dump collection
- `--intelligent-filter` — Collapse framework internals, focus on app code
- `-l, --live` — Live mode: interactive TUI with continuous monitoring
- `--color` — Enable colored output in live mode
- `-f, --file=<zip>` — Replay mode from a recording ZIP
- `--no-native` — Ignore threads without stack traces

**Exit codes:**
- `0` = no issues
- `1` = no JVMs found (for `list`)
- `2` = deadlock detected
- `10` = JVM is totally outdated (> 1 year)

---

### `status` (default)

Runs deadlock detection, most-work, threads, dependency-graph, and dependency-tree over shared thread dumps. Requires at least 2 dumps.

Also performs a **JVM support check**: collects `java.version.date` from `jcmd VM.system_properties` and warns if the JVM is > 4 months old (exit code 10 if > 1 year old).

```bash
jstall status 12345
jstall status MyApplication --top 5 --intelligent-filter
jstall status all --full
```

---

### `most-work`

Shows CPU time, CPU percentage, core utilization, state distribution, and activity categorization for top threads.

```bash
jstall most-work 12345 --top 5 --stack-depth 15 --intelligent-filter
```

---

### `waiting-threads`

Finds threads stuck waiting on the same lock instance across all dumps with no CPU progress (WAITING/TIMED_WAITING, CPU ≤ 0.0001s).

```bash
jstall waiting-threads 12345 --intelligent-filter
```

---

### `dependency-graph`

Shows which threads wait on locks held by others, detects dependency chains, and categorizes threads by activity.

**Example Output:**
```
Thread Dependency Graph
======================

[I/O Write] file-writer
  → [Network] netty-worker-1 (lock: <0xBBBB>)
     Waiter state: BLOCKED, CPU: 2.10s
     Owner state:  BLOCKED, CPU: 5.20s

Dependency Chains Detected:
---------------------------
Chain: [Database] jdbc-connection-pool → [I/O Write] file-writer → [Network] netty-worker-1
```

---

### `flame`

Generates a flamegraph using [async-profiler](https://github.com/async-profiler/async-profiler). Filter must match exactly one JVM.

```bash
jstall flame 12345 --duration 15s --output flame.html
jstall flame MyApp --event alloc --duration 20s --open
```

Events: `cpu`, `alloc`, `lock`, `wall`, `itimer`

---

### `compiler-queue`

Analyzes JIT compiler queue state over time using `jcmd Compiler.queue`.

```bash
jstall compiler-queue 12345
```

---

### Live Mode

Monitor a JVM continuously with an interactive TUI (Linux/macOS only):

```bash
jstall status 12345 --live
jstall status MyApp --live --color
jstall threads 12345 --live --color
```

> **Note:** `--live` requires a Unix-like environment (Linux, macOS, or WSL). It is not available on Windows.

**Features:**
- Async data collection (UI stays responsive)
- Tab navigation between analyzers (Tab/Shift-Tab)
- Sort by column (`1-9`), secondary sort (`s` then `1-9`)
- Filter rows (`/`)
- Adjust collection interval (`+`/`-`), force refresh (`r`)
- Colored output with `--color` (green=RUNNABLE, red=BLOCKED, yellow=WAITING, CPU% intensity)
- Scroll (`j`/`k` or arrows), horizontal pan (`h`/`l`)

---

## Recording & Replay

Record diagnostic data for later analysis or sharing:

```bash
jstall record 12345 -o myapp-diagnostics.zip
jstall record all -o full-system.zip --full   # includes expensive jcmd commands
```

Replay on any machine:

```bash
jstall status myapp-diagnostics.zip
jstall -f myapp-diagnostics.zip threads all
jstall record summary myapp-diagnostics.zip
jstall record extract myapp-diagnostics.zip ./output-folder
```

---

## Thread Activity Categorization

JStall automatically categorizes threads by their activity based on stack trace analysis:

| Category | Description |
|----------|-------------|
| Network Read/Write | Socket operations, accept calls |
| Network | Selectors, polling, Netty |
| I/O Read/Write | File input/output operations |
| Database | JDBC and SQL operations |
| External Process | Waiting on external processes |
| Lock Wait | Waiting on locks/monitors |
| Sleep | Thread.sleep() calls |
| Park | LockSupport.park() calls |
| Computation | Active computation |
| Unknown | Unrecognized activity |

---

## Intelligent Stack Trace Filtering

Use `--intelligent-filter` to collapse framework internals and focus on application code.

```bash
jstall most-work 12345 --intelligent-filter --stack-depth 15
```

**Before:**
```
at com.example.MyController.handleRequest(MyController.java:42)
at jdk.internal.reflect.GeneratedMethodAccessor123.invoke(Unknown Source)
at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(...)
at java.lang.reflect.Method.invoke(Method.java:566)
at org.springframework.web.method.support.InvocableHandlerMethod.invoke(...)
```

**After:**
```
at com.example.MyController.handleRequest(MyController.java:42)
... (3 internal frames omitted)
at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(...)
at com.example.Service.processRequest(Service.java:78)
```

---

## Usage as a Library

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jstall</artifactId>
    <version>0.6.2</version>
</dependency>
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, test configuration, and how to extend JStall.

## Support & Feedback

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub issues](https://github.com/parttimenerd/jstall/issues).
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors