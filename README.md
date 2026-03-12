# JStall

[![CI](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/jstall)](https://central.sonatype.com/artifact/me.bechberger/jstall)

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
* **AI-powered analysis**: Get intelligent insights from thread dumps using LLMs (supports local models via Ollama)
* **Record & Replay**: Record diagnostic data for later analysis or sharing as a zip file

Requires Java 17+ to run.

## Quick Start

**Example:** Find out what your application (in our example `MyApplication` with pid `12345`) is doing right now

```bash
# Quick status check (checks for deadlocks and hot threads)
jstall 12345

# Or explicitly run the status command, that also supports using JVM name filters
jstall status MyApplication

# AI-powered analysis with intelligent insights
jstall ai 12345

# Analyze all JVMs on the system with AI
jstall ai full

# Find threads consuming most CPU
jstall most-work 12345

# Detect threads stuck waiting on locks
jstall waiting-threads 12345

# Show thread dependency graph (which threads wait on which)
jstall dependency-graph 12345

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

### JVM support check (part of `status`)

When analyzing a live JVM (by PID or filter), JStall also collects `jcmd VM.system_properties` and checks `java.version.date`:

- **<= 4 months old**: emit nothing (JVM is considered "young enough")
- **> 4 months old**: show a hint that you might want to update
- **> 1 year old**: treat as **totally outdated** and return a non-zero exit code

This is meant as a lightweight guardrail: if your JVM is very old, analysis results and production behavior can be misleading.

### Installation

Download the latest executable from the [releases page](https://github.com/parttimenerd/jstall/releases).

Or use with [JBang](https://www.jbang.dev/): `jbang jstall@parttimenerd/jstall <pid>`

### Usage

<!-- BEGIN help -->
```bash
Usage: jstall [-hV] [--file=<replayFile>] [COMMAND]
One-shot JVM inspection tool
  -f, --file=<replayFile>    File path for replay mode (replay ZIP file created
                             by record command)
  -h, --help                 Show this help message and exit.
  -V, --version              Print version information and exit.
Commands:
  record                Record all data into a zip for later analysis
  status                Run multiple analyzers over thread dumps (default command)
  deadlock              Detect JVM-reported thread deadlocks
  most-work             Identify threads doing the most work across dumps
  flame                 Generate a flamegraph of the application using async-profiler
  threads               List all threads sorted by CPU time
  waiting-threads       Identify threads waiting without progress (potentially starving)
  dependency-graph      Show thread dependencies (which threads wait on locks held by others)
  vm-vitals             Show VM.vitals (if available)
  gc-heap-info          Show GC.heap_info last absolute values and change
  vm-classloader-stats  Show VM.classloader_stats grouped by classloader type
  vm-metaspace          Show VM.metaspace summary and trend
  compiler-queue        Analyze compiler queue state showing active compilations and queued tasks
  ai                    AI-powered thread dump analysis using LLM
  list                  List running JVM processes (excluding this tool)
  processes             Detect other processes running on the system that consume high CPU time
  jvm-support           Check whether the target JVM is likely still supported (based on java.version.date)
```
<!-- END help -->

### Usage as a library

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jstall</artifactId>
    <version>0.5.2</version>
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
Usage: jstall status [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                     [--intelligent-filter] [--full] [--top=<top>] [--no-native] [<targets>...]
Run multiple analyzers over thread dumps (default command)
      [<targets>...]       PID, 'all', filter or dump files (or replay ZIP as
                           first argument)
      --dumps=<dumps>      Number of dumps to collect, default is none
      --full               Run all analyses including expensive ones (only for
                           status command)
  -h, --help               Show this help message and exit.
      --intelligent-filter Use intelligent stack trace filtering (collapses
                           internal frames, focuses on application code)
      --interval=<interval>
                           Interval between dumps, default is 5s
      --keep               Persist dumps to disk
      --no-native          Ignore threads without stack traces (typically
                           native/system threads)
      --top=<top>          Number of top threads (default: 3)
  -V, --version            Print version information and exit.
```
<!-- END help_status -->

**Exit codes:**
- `0` = no issues
- `2` = deadlock detected
- `10` = JVM is totally outdated (> 1 year based on `java.version.date`)

**Note:** Supports multiple targets analyzed in parallel.

---

### `jvm-support`

Checks whether the target JVM is reasonably up-to-date based on `java.version.date` from `jcmd VM.system_properties`.

<!-- BEGIN help_jvm_support -->
```
Usage: jstall jvm-support [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                          [--intelligent-filter] [<targets>...]
```
<!-- END help_jvm_support -->

**Exit codes:**
- `0` = JVM is supported / only mildly outdated
- `10` = JVM is totally outdated (> 1 year based on `java.version.date`)

---

### `most-work`

<!-- BEGIN help_most_work -->
```
Usage: jstall most-work [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                        [--intelligent-filter] [--full] [--top=<top>] [--no-native]
                        [--stack-depth=<stackDepth>] [<targets>...]
Identify threads doing the most work across dumps
      [<targets>...]            PID, 'all', filter or dump files (or replay ZIP
                                as first argument)
      --dumps=<dumps>           Number of dumps to collect, default is none
      --full                    Run all analyses including expensive ones (only
                                for status command)
  -h, --help                    Show this help message and exit.
      --intelligent-filter      Use intelligent stack trace filtering (collapses
                                internal frames, focuses on application code)
      --interval=<interval>     Interval between dumps, default is 5s
      --keep                    Persist dumps to disk
      --no-native               Ignore threads without stack traces (typically
                                native/system threads)
      --stack-depth=<stackDepth>
                                Stack trace depth to show (default: 10, 0=all,
                                in intelligent mode: max relevant frames)
      --top=<top>               Number of top threads to show (default: 3)
  -V, --version                 Print version information and exit.
```
<!-- END help_most_work -->

Shows CPU time, CPU percentage, core utilization, state distribution, and activity categorization for top threads.

---

### `deadlock`

<!-- BEGIN help_deadlock -->
```
Usage: jstall deadlock [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                       [--intelligent-filter] [--full] [<targets>...]
Detect JVM-reported thread deadlocks
      [<targets>...]       PID, 'all', filter or dump files (or replay ZIP as
                           first argument)
      --dumps=<dumps>      Number of dumps to collect, default is none
      --full               Run all analyses including expensive ones (only for
                           status command)
  -h, --help               Show this help message and exit.
      --intelligent-filter Use intelligent stack trace filtering (collapses
                           internal frames, focuses on application code)
      --interval=<interval>
                           Interval between dumps, default is 5s
      --keep               Persist dumps to disk
  -V, --version            Print version information and exit.
```
<!-- END help_deadlock -->

**Exit codes:** `0` = no deadlock, `2` = deadlock detected

---

### `threads`

Lists all threads sorted by CPU time in a table format.

<!-- BEGIN help_threads -->
```
Usage: jstall threads [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                      [--intelligent-filter] [--full] [--no-native] [<targets>...]
List all threads sorted by CPU time
      [<targets>...]       PID, 'all', filter or dump files (or replay ZIP as
                           first argument)
      --dumps=<dumps>      Number of dumps to collect, default is none
      --full               Run all analyses including expensive ones (only for
                           status command)
  -h, --help               Show this help message and exit.
      --intelligent-filter Use intelligent stack trace filtering (collapses
                           internal frames, focuses on application code)
      --interval=<interval>
                           Interval between dumps, default is 5s
      --keep               Persist dumps to disk
      --no-native          Ignore threads without stack traces (typically
                           native/system threads)
  -V, --version            Print version information and exit.
```
<!-- END help_threads -->

Shows thread name, CPU time, CPU %, state distribution, activity categorization, and top stack frame.

---

### `waiting-threads`

Identifies threads waiting on the same lock instance across all dumps with no CPU progress.

<!-- BEGIN help_waiting_threads -->
```
Usage: jstall waiting-threads [-hV] [--dumps=<dumps>] [--interval=<interval>]
                              [--keep] [--intelligent-filter] [--full] [--no-native]
                              [--stack-depth=<stackDepth>] [<targets>...]
Identify threads waiting without progress (potentially starving)
      [<targets>...]            PID, 'all', filter or dump files (or replay ZIP
                                as first argument)
      --dumps=<dumps>           Number of dumps to collect, default is none
      --full                    Run all analyses including expensive ones (only
                                for status command)
  -h, --help                    Show this help message and exit.
      --intelligent-filter      Use intelligent stack trace filtering (collapses
                                internal frames, focuses on application code)
      --interval=<interval>     Interval between dumps, default is 5s
      --keep                    Persist dumps to disk
      --no-native               Ignore threads without stack traces (typically
                                native/system threads)
      --stack-depth=<stackDepth>
                                Stack trace depth to show (1=inline, 0=all,
                                default: 1, in intelligent mode: max relevant
                                frames)
  -V, --version                 Print version information and exit.
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

### `compiler-queue`

Analyzes JIT compiler queue state over time using `jcmd Compiler.queue`. Shows active compilations and queued compilation tasks across multiple samples.

**Usage:**
```bash
jstall compiler-queue <pid>
jstall compiler-queue <filter>
```

**Features:**
- Shows full time-series trend across collected samples
- Displays active compilations (currently running on compiler threads)
- Shows queued tasks per compiler queue (C1, C2, etc.)
- Provides min/max/latest statistics for queue depths
- Per-sample breakdown with timestamp and queue details
- Detailed view of latest snapshot with task information

**Example Output:**
```
Compiler queue trend (3 samples):

Summary:
  Active compilations: 1 (range: 0-2)
  Queued tasks: 5 (range: 3-7)

Per-sample breakdown:
Time      Active  Queued  Queues Detail
--------  ------  ------  -------------
14:23:10       2       7  C1:4, C2:3
14:23:12       1       5  C1:2, C2:3
14:23:14       1       3  C1:1, C2:2

Latest snapshot details:
Active compilations:
  [123] T2 OSR java.lang.String.indexOf @ 10 (42 bytes)

C1 compile queue: 1 task(s)
  [124] T1 com.example.Foo.bar (128 bytes)

C2 compile queue: 2 task(s)
  [125] T2 BLOCK java.util.HashMap.get (256 bytes)
  [126] T2 com.example.Service.process (512 bytes)
```

**Options:**
- `--samples=<n>` — Number of samples to collect (default: 3)
- `--interval=<ms>` — Interval between samples in milliseconds (default: 2000)

**Notes:**
- Requires JVM support for `jcmd Compiler.queue` (HotSpot/OpenJDK/SapMachine)
- Informational output only (no warning thresholds)
- Included in `status` command output
- Parses compiler attributes: OSR, blocking, synchronized, tier information

**Exit codes:** `0` = success (informational only)

---

### `ai`

AI-powered thread dump analysis using a Large Language Model (LLM). Combines status analysis with intelligent AI interpretation.

<!-- BEGIN help_ai -->
```
Usage: jstall ai [-hV] [--dry-run] [--intelligent-filter] [--keep] [--no-native]
                 [--raw] [--dumps=<dumps>] [--interval=<interval>]
                 [--model=<model>] [--question=<question>]
                 [--stack-depth=<stackDepth>] [--top=<top>] [<targets>...]
AI-powered thread dump analysis using LLM
      [<targets>...]    PID, filter or dump files
      --dumps=<dumps>   Number of dumps to collect, default is 2
      --dry-run         Perform a dry run without calling the AI API
  -h, --help            Show this help message and exit.
      --intelligent-filter
                        Use intelligent stack trace filtering (collapses
                          internal frames, focuses on application code)
      --interval=<interval>
                        Interval between dumps, default is 5s
      --keep            Persist dumps to disk
      --model=<model>   LLM model to use (default: gpt-50-nano)
      --no-native       Ignore threads without stack traces (typically
                          native/system threads)
      --question=<question>
                        Custom question to ask (use '-' to read from stdin)
      --raw             Output raw JSON response
      --stack-depth=<stackDepth>
                        Stack trace depth to show (default: 10, 0=all, in
                          intelligent mode: max relevant frames)
      --top=<top>       Number of top threads (default: 3)
  -V, --version         Print version information and exit.
```
<!-- END help_ai -->

**Features:**
- Runs comprehensive status analysis (deadlocks, hot threads, dependency graph)
- Sends analysis to LLM for intelligent interpretation
- Provides natural language insights and recommendations
- Supports custom questions about the thread dumps
- Intelligent filtering enabled by default

**Setup:**

**Option 1: Local Models (Ollama)**  
Run AI analysis with local models for privacy and no API costs:
1. Install [Ollama](https://ollama.ai/)
2. Pull a model: `ollama pull qwen3:30b`
3. Create `.jstall-ai-config` in your home directory or current directory:
   ```properties
   provider=ollama
   model=qwen3:30b
   ollama.host=http://127.0.0.1:11434
   ```
**Note:** Ensure the Ollama server is running before using JStall with local models.

It takes some time to start the model on first use; subsequent calls are faster, therefore:

```bash
OLLAMA_KEEP_ALIVE=1000m0s OLLAMA_CONTEXT_LENGTH=32000 OLLAMA_MAX_LOADED_MODELS=1 OLLAMA_HOST=http://127.0.0.1:11434 ollama serve
```
And also prime the model with a dummy request:

```bash
ollama chat qwen3:30b --prompt "Hello"
```

The `qwen3:30b` model is recommended for best results, but others can be used as well.
It takes 18GB of RAM when loaded and runs reasonably fast on my MacBook Pro M4 48GB.

Maybe also `gpt-oss:20b` works.

**Option 2: Gardener AI (Remote)**  
Use the Gardener AI API service:
- Create a `.gaw` file containing your API key in one of these locations:
  - Current directory: `./.gaw`
  - Home directory: `~/.gaw`
  - Or set environment variable: `ANSWERING_MACHINE_APIKEY`
- Or configure in `.jstall-ai-config`:
  ```properties
  provider=gardener
  model=gpt-50-nano
  api.key=your-api-key-here
  ```

**Note:** Ollama supports true token-by-token streaming and thinking mode (`--thinking`), while Gardener AI returns complete responses.

**Examples:**

```bash
# Basic AI analysis (uses config from .jstall-ai-config)
jstall ai 12345

# Use local Ollama (override config)
jstall ai --local 12345

# Use remote Gardener AI (override config)
jstall ai --remote 12345

# Basic AI analysis with short summary at the end
jstall ai 12345 --short

# Show thinking process (Ollama only - displays model's reasoning)
jstall ai 12345 --thinking

# Use local model with thinking mode
jstall ai --local --thinking 12345

# Ask a specific question
jstall ai 12345 --question "Why is my application slow?"

# Read question from stdin
echo "What's causing high memory usage?" | jstall ai 12345 --question -

# Dry run to see the prompt without API call
jstall ai 12345 --dry-run

# Use a different model (override config)
jstall ai 12345 --model qwen3:30b
```

**Exit codes:** `0` = success, `2` = API key not found, `4` = authentication failed, `5` = API error, `3` = network error

---

### `ai full`

Analyzes **all active JVMs on the system** with AI-powered insights. Discovers running JVMs, analyzes those using CPU, and provides system-wide analysis.

<!-- BEGIN help_ai_full -->
```
Usage: jstall ai full [-hV] [--dry-run] [--intelligent-filter] [--no-native]
                      [--raw] [--cpu-threshold=<cpuThreshold>]
                      [-i=<interval>] [--model=<model>] [-n=<dumps>]
                      [--question=<question>] [--stack-depth=<stackDepth>]
                      [--top=<top>]
Analyze all JVMs on the system with AI
      --cpu-threshold=<cpuThreshold>
                        CPU threshold percentage (default: 1.0%)
      --dry-run         Perform a dry run without calling the AI API
  -h, --help            Show this help message and exit.
  -i, --interval=<interval>
                        Interval between dumps in seconds (default: 1)
      --intelligent-filter
                        Enable intelligent stack filtering (default: true)
      --model=<model>   LLM model to use (default: gpt-50-nano)
  -n, --dumps=<dumps>   Number of dumps per JVM (default: 2)
      --no-native       Ignore threads without stack traces
      --question=<question>
                        Custom question to ask (use '-' to read from stdin)
      --raw             Output raw JSON response
      --stack-depth=<stackDepth>
                        Stack trace depth (default: 10, 0=all)
      --top=<top>       Number of top threads per JVM (default: 3)
  -V, --version         Print version information and exit.
```
<!-- END help_ai_full -->

**How it works:**
1. Discovers all JVMs on the system
2. Collects thread dumps from each JVM (in parallel)
3. Filters JVMs by CPU usage (default: >1% of interval time)
4. Runs status analysis on each active JVM
5. Sends combined analysis to AI for system-wide insights

**Output structure:**
1. High-level summary of overall system state
2. Cross-JVM issues, bottlenecks, or patterns
3. Individual analysis sections for each JVM

**Examples:**

```bash
# Analyze all active JVMs on the system
jstall ai full

# Lower CPU threshold to include more JVMs
jstall ai full --cpu-threshold 0.5

# Focus on specific concern
jstall ai full --question "Which JVMs have memory leak indicators?"

# Dry run to see what would be analyzed
jstall ai full --dry-run

# More comprehensive analysis with more dumps
jstall ai full -n 5 -i 2
```

**Use cases:**
- Production environment health check
- Microservices ecosystem analysis  
- Identify system-wide bottlenecks
- Cross-service dependency issues
- Resource usage patterns across multiple JVMs

**Exit codes:** Same as `ai` command

---

## Recording & Replay

You can record diagnostic data with:
```bash
jstall record <all|pid|filter> -o <recording.zip>
```

and replay it on any machine using the `-f/--file` option. For an example recording in this repository see the folder: [6529/](./6529/)

If no target JVM can be resolved (for example, a PID that does not exist), recording fails early with:

```text
No JVM targets found for: <target>
```

Replay examples (use your recording ZIP file):

```bash
# Replay a recording and run the status analyzers
jstall -f <recording.zip> status all

# Replay a recording and list threads
jstall -f <recording.zip> threads all

# Or with the file as a positional argument
jstall threads <recording.zip>
```

Archive utility commands:

```bash
# Print the recording README summary from the ZIP
jstall record summary <recording.zip>

# Extract the recording ZIP into a destination folder
jstall record extract <recording.zip> <output-folder>
```

The extract command writes the full archive content (README, metadata, per-PID folders) under the destination folder.

When replaying, JStall will use the recorded data files instead of querying a live JVM. If a tool needs additional data that wasn't recorded, it will skip that analysis and continue with available information.

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

### Intelligent Stack Trace Filtering

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

### `processes`

Checks whether there are any processes running on the system that take a high amount of CPU. 
Helpful to identify e.g. a virus scanner or other interfering processes that use more than 20% of the available CPU-time. 
Also report if non-own processes are consuming more than 40% of CPU time. 
In either of these cases, list all processes with a CPU usage above 1% of CPU time.

<!-- BEGIN help_processes -->
```
Usage: jstall processes [-hV] [--cpu-threshold=<cpuThreshold>]
                          [--own-process-cpu-threshold=<ownProcessCpuThreshold>]

```                          
<!-- END help_processes -->

### `flame`

Generates a flamegraph using async-profiler.

<!-- BEGIN help_flame -->
```
Usage: jstall flame [-hV] [--output=<outputFile>] [--duration=<duration>]
                    [--event=<event>] [--interval=<interval>] [--open] [<target>]
Generate a flamegraph of the application using async-profiler
      [<target>]               PID or filter (filters JVMs by main class name)
  -d, --duration=<duration>    Profiling duration (default: 10s), default is 10s
  -e, --event=<event>          Profiling event (default: cpu). Options: cpu,
                               alloc, lock, wall, itimer
  -h, --help                   Show this help message and exit.
  -i, --interval=<interval>    Sampling interval (default: 10ms), default is
                               10ms
  -o, --output=<outputFile>    Output HTML file (default: flame.html)
      --open                   Automatically open the generated HTML file in
                               browser
  -V, --version                Print version information and exit.

Examples:
  jstall flame 12345 --output flame.html --duration 15s
  # Allocation flamegraph for a JVM running MyAppMainClass with a 20s duration
  # open flamegraph automatically after generation
  jstall flame MyAppMainClass --event alloc --duration 20s --open
```
<!-- END help_flame -->

**Note:** Filter must match exactly one JVM. Uses [async-profiler](https://github.com/async-profiler/async-profiler).

## Development

### Building and Testing

```bash
mvn clean package
```

[bin/sync-documentation.py](bin/sync-documentation.py) is used to synchronize the CLI help messages into this README.

[release.py](./release.py) is a helper script to create new releases.

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