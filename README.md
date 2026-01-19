# JStall

[![CI](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/jstall)](https://central.sonatype.com/artifact/me.bechberger/jstall)

**JStall answers the age-old question: "What is my Java application doing right now?"**

JStall is a small command-line tool for **one-shot inspection** of running JVMs using thread dumps and short, on-demand profiling.

Features:
* **Deadlock detection** — Find JVM-reported deadlocks quickly
* **Hot thread identification** — See which threads are doing the most work
* **Thread activity categorization** — Automatically classify threads by activity (I/O, Network, Database, etc.)
* **Dependency graph** — Visualize which threads wait on locks held by others
* **Starvation detection** — Find threads waiting on the same lock with no progress
* **Intelligent stack filtering** — Collapse framework internals, focus on application code
* **Offline analysis** — Analyze existing thread dumps
* **Flamegraph generation** — Short profiling runs with [async-profiler](https://github.com/async-profiler/async-profiler)
* **Smart filtering** — Target JVMs by name/class instead of PID
* **Multi-execution** — Analyze multiple JVMs in parallel for faster results
* **Supports Java 11+** — Works with all modern Java versions

## Quick Start

**Example:** Find out what your application (in our example `MyApplication` with pid `12345`) is doing right now

```bash
# Quick status check (checks for deadlocks and hot threads)
jstall 12345

# Or explicitly run the status command, that also supports using JVM name filters
jstall status MyApplication

# Find threads consuming most CPU
jstall most-work 12345

# Detect threads stuck waiting on locks
jstall waiting-threads 12345

# Show thread dependency graph (which threads wait on which)
jstall dependency-graph 12345

# Generate a flamegraph
jstall flame 12345
```

### Installation

Download the latest executable from the [releases page](https://github.com/parttimenerd/jstall/releases).

Or use with [JBang](https://www.jbang.dev/): `jbang jstall@parttimenerd/jstall <pid>`

### Usage

```bash
> jstall --help
Usage: jstall [-hV] [COMMAND]
One-shot JVM inspection tool
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  status            Run multiple analyzers over thread dumps (default command)
  deadlock          Detect JVM-reported thread deadlocks
  most-work         Identify threads doing the most work across dumps
  flame             Generate a flamegraph of the application using async-profiler
  threads           List all threads sorted by CPU time
  waiting-threads   Identify threads waiting without progress (potentially
                      starving)
  dependency-graph  Show thread dependencies (lock wait relationships)
  list              List running JVM processes (excluding this tool)
```

### Usage as a library

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jstall</artifactId>
    <version>0.4.3</version>
</dependency>
```

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

### `list`

<!-- BEGIN help_list -->
```
Usage: jstall list [-hV] [<filter>]
List running JVM processes (excluding this tool)
      [<filter>]   Optional filter - only show JVMs whose main class contains
                     this text
  -h, --help       Show this help message and exit.
  -V, --version    Print version information and exit.
```
<!-- END help_list -->

**Example:**
```bash
> jstall list kafka
   67890    org.apache.kafka.Kafka
```

**Exit codes:** `0` = JVMs found, `1` = no JVMs found

---

### `status` (default)

Runs multiple analyzers (deadlock, most-work, threads, dependency-graph) over shared thread dumps.

<!-- BEGIN help_status -->
```
Usage: jstall status [-hV] [--intelligent-filter] [--keep] [--no-native]
                     [--dumps=<dumps>] [--interval=<interval>]
                     [--stack-depth=<stackDepth>] [--top=<top>] [<targets>...]
Run multiple analyzers over thread dumps (default command)
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
  -h, --help            Show this help message and exit.
      --intelligent-filter
                        Use intelligent stack trace filtering (collapses
                          internal frames, focuses on application code)
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
      --no-native       Ignore threads without stack traces (typically
                          native/system threads)
      --stack-depth=<stackDepth>
                        Stack trace depth to show (default: 10, 0=all, in
                          intelligent mode: max relevant frames)
      --top=<top>       Number of top threads (default: 3)
  -V, --version         Print version information and exit.
```
<!-- END help_status -->

**Exit codes:** `0` = no issues, `2` = deadlock detected

**Note:** Supports multiple targets analyzed in parallel.

---

### `most-work`

<!-- BEGIN help_most_work -->
```
Usage: jstall most-work [-hV] [--intelligent-filter] [--keep] [--no-native]
                        [--dumps=<dumps>] [--interval=<interval>]
                        [--stack-depth=<stackDepth>] [--top=<top>]
                        [<targets>...]
Identify threads doing the most work across dumps
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
  -h, --help            Show this help message and exit.
      --intelligent-filter
                        Use intelligent stack trace filtering (collapses
                          internal frames, focuses on application code)
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
      --no-native       Ignore threads without stack traces (typically
                          native/system threads)
      --stack-depth=<stackDepth>
                        Stack trace depth to show (default: 10, 0=all, in
                          intelligent mode: max relevant frames)
      --top=<top>       Number of top threads to show (default: 3)
  -V, --version         Print version information and exit.
```
<!-- END help_most_work -->

Shows CPU time, CPU percentage, core utilization, state distribution, and activity categorization for top threads.

---

### `deadlock`

<!-- BEGIN help_deadlock -->
```
Usage: jstall deadlock [-hV] [--keep] [--dumps=<dumps>] [--interval=<interval>]
                       [<targets>...]
Detect JVM-reported thread deadlocks
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
  -h, --help            Show this help message and exit.
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
  -V, --version         Print version information and exit.
```
<!-- END help_deadlock -->

**Exit codes:** `0` = no deadlock, `2` = deadlock detected

---

### `threads`

Lists all threads sorted by CPU time in a table format.

<!-- BEGIN help_threads -->
```
Usage: jstall threads [-hV] [--keep] [--no-native] [--dumps=<dumps>]
                      [--interval=<interval>] [--top=<top>] [<targets>...]
List all threads sorted by CPU time
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
  -h, --help            Show this help message and exit.
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
      --no-native       Ignore threads without stack traces (typically
                          native/system threads)
      --top=<top>       Maximum number of threads to show (default: all)
  -V, --version         Print version information and exit.
```
<!-- END help_threads -->

Shows thread name, CPU time, CPU %, state distribution, activity categorization, and top stack frame.

---

### `waiting-threads`

Identifies threads waiting on the same lock instance across all dumps with no CPU progress.

<!-- BEGIN help_waiting_threads -->
```
Usage: jstall waiting-threads [-hV] [--intelligent-filter] [--keep]
                              [--no-native] [--dumps=<dumps>]
                              [--interval=<interval>]
                              [--stack-depth=<stackDepth>] [<targets>...]
Identify threads waiting without progress (potentially starving)
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
  -h, --help            Show this help message and exit.
      --intelligent-filter
                        Use intelligent stack trace filtering (collapses
                          internal frames, focuses on application code)
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
      --no-native       Ignore threads without stack traces (typically
                          native/system threads)
      --stack-depth=<stackDepth>
                        Stack trace depth to show (1=inline, 0=all, default: 1,
                          in intelligent mode: max relevant frames)
  -V, --version         Print version information and exit.
```
<!-- END help_waiting_threads -->

**Detection criteria:** Thread in ALL dumps, WAITING/TIMED_WAITING state, CPU ≤ 0.0001s, same lock instance.

Highlights lock contention when multiple threads are blocked on the same lock.

---

### `dependency-graph`

Shows thread dependencies by visualizing which threads wait on locks held by other threads.

<!-- BEGIN help_dependency_graph -->
```
Usage: jstall dependency-graph [-hV] [--keep] [--dumps=<dumps>]
                               [--interval=<interval>] [<targets>...]
Show thread dependencies (which threads wait on locks held by others)
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
  -h, --help            Show this help message and exit.
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
  -V, --version         Print version information and exit.
```
<!-- END help_dependency_graph -->

**Features:**
- Shows which threads wait on locks held by others
- Categorizes threads by activity (I/O, Network, Database, Computation, etc.)
- Detects dependency chains (A waits on B, B waits on C, etc.)
- Displays thread states and CPU times
- Uses the latest dump when multiple dumps are provided

**Example Output:**
```
Thread Dependency Graph
======================

[I/O Write] file-writer
  → [Network] netty-worker-1 (lock: <0xBBBB>)
     Waiter state: BLOCKED, CPU: 2.10s
     Owner state:  BLOCKED, CPU: 5.20s

[Database] jdbc-connection-pool
  → [I/O Write] file-writer (lock: <0xAAAA>)
     Waiter state: BLOCKED, CPU: 15.70s
     Owner state:  BLOCKED, CPU: 2.10s

Summary:
--------
Total waiting threads: 2
Total dependencies: 2

Dependency Chains Detected:
---------------------------
Chain: [Database] jdbc-connection-pool → [I/O Write] file-writer → [Network] netty-worker-1
```

---

## Thread Activity Categorization

JStall automatically categorizes threads by their activity based on stack trace analysis:

**Categories:**
- **Network Read/Write** — Socket operations, accept calls
- **Network** — Selectors, polling, Netty, file system monitoring  
- **I/O Read/Write** — File input/output operations
- **I/O** — General file I/O
- **Database** — JDBC and SQL operations
- **External Process** — Process handling, waiting on external processes
- **Lock Wait** — Threads waiting on locks/monitors
- **Sleep** — Thread.sleep() calls
- **Park** — LockSupport.park() calls
- **Computation** — Active computation
- **Unknown** ��� Unrecognized activity

Categories appear in `most-work`, `threads`, and `dependency-graph` command outputs.

**Example:**
```
1. netty-worker-1
   CPU time: 10.50s (45.0% of total)
   States: RUNNABLE: 100.0%
   Activity: Network
   Common stack prefix:
     sun.nio.ch.KQueue.poll(Native Method)
     ...
```

---

## Intelligent Stack Trace Filtering

Use `--intelligent-filter` to automatically collapse framework internals and focus on application code and important operations.

**Available on:** `most-work`, `waiting-threads`, `status`

**What it does:**
- Collapses JDK internals, reflection, proxies, generated code
- Preserves application code
- Keeps important operations visible: I/O, Network, Database, Threading
- Respects `--stack-depth` for relevant frames (not total frames)

**Example:**
```bash
# Show top threads with clean stack traces
jstall most-work 12345 --intelligent-filter --stack-depth 15

# Analyze waiting threads with focused stack traces
jstall waiting-threads 12345 --intelligent-filter --stack-depth 10
```

**Normal output:**
```
Stack:
  at com.example.MyController.handleRequest(MyController.java:42)
  at jdk.internal.reflect.GeneratedMethodAccessor123.invoke(Unknown Source)
  at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
  at java.lang.reflect.Method.invoke(Method.java:566)
  at org.springframework.web.method.support.InvocableHandlerMethod.invoke(InvocableHandlerMethod.java:205)
  ... (15 more frames)
```

**With `--intelligent-filter`:**
```
Stack:
  at com.example.MyController.handleRequest(MyController.java:42)
  ... (3 internal frames omitted)
  at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:150)
  at com.example.Service.processRequest(Service.java:78)
  at java.sql.Connection.executeQuery(Connection.java:100)
  at com.example.Repository.findUser(Repository.java:45)
```

---

### `flame`

Generates a flamegraph using async-profiler.

<!-- BEGIN help_flame -->
```
Usage: jstall flame [-hV] [--open] [-d=<duration>] [-e=<event>] [-i=<interval>]
                    [-o=<outputFile>] [<target>]
Generate a flamegraph of the application using async-profiler
      [<target>]        PID or filter (filters JVMs by main class name)
  -d, --duration=<duration>
                        Profiling duration (default: 10s)
  -e, --event=<event>   Profiling event (default: cpu). Options: cpu, alloc,
                          lock, wall, itimer
  -h, --help            Show this help message and exit.
  -i, --interval=<interval>
                        Sampling interval (default: 10ms)
  -o, --output=<outputFile>
                        Output HTML file (default: flame.html)
      --open            Automatically open the generated HTML file in browser
  -V, --version         Print version information and exit.
```
<!-- END help_flame -->

**Note:** Filter must match exactly one JVM. Uses [async-profiler](https://github.com/async-profiler/async-profiler).

## Development

### Building and Testing

```bash
mvn clean package
```

[bin/sync-documentation.py](bin/sync-documentation.py) is used to synchronize the CLI help messages into this README.

[release.sh](./release.sh) is a helper script to create new releases.

### Extending

Extend this tool by adding new analyzers. You can do this by implementing an analysis, creating a new command,
and adding it to the main CLI class (and adding the analysis optionally to the status command).
Please also update the README accordingly.

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors