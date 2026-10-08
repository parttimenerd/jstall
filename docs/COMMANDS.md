# JStall Command Reference

Full CLI help output for all JStall commands. For a quick overview, see the [README](../README.md#commands).

## Global Options

<!-- BEGIN help -->
```bash
Usage: jstall [-hV] [--file=<replayFile>] [--ssh=<sshCommandPrefix>]
              [--cf=<cfAppName>] [--verbose] [--dry-run] [COMMAND]
One-shot JVM inspection tool
      --cf=<cfAppName>            Use Cloud Foundry CLI for remote execution
                                  (shortcut for --ssh 'cf ssh <app-name> -c'),
                                  only Linux/Mac support on remote
      --dry-run                   Print the command that would run instead of
                                  executing it
  -f, --file=<replayFile>         File path for replay mode (replay ZIP file
                                  created by record command)
  -h, --help                      Show this help message and exit.
  -s, --ssh=<sshCommandPrefix>    Execution command prefix for running commands
                                  on a remote host via SSH (e.g., 'ssh
                                  user@host'), only Linux/Mac support on remote
  -v, --verbose                   Enable verbose logging of remote SSH commands
                                  and their outputs
  -V, --version                   Print version information and exit.
Commands:
  record                Record all data into a zip for later analysis
  status                Best first check: summarize JVM health, hot threads, memory, deadlocks, and lock contention
  deadlock              Detect JVM-reported thread deadlocks
  most-work             Identify threads doing the most work across dumps
  flame                 Generate a flamegraph of the application using async-profiler
  threads               List all threads sorted by CPU time
  waiting-threads       Identify threads waiting without progress (potentially starving)
  dependency-graph      Show thread dependencies
  dependency-tree       Show non deadlock thread dependencies over time
  vm-vitals             SapMachine-only: show recent JVM/process/system vitals, trends, and extremes
  gc-heap-info          Show GC.heap_info last absolute values and change
  vm-classloader-stats  Show VM.classloader_stats grouped by classloader type
  vm-metaspace          Show VM.metaspace summary and trend
  compiler-queue        Analyze compiler queue state showing active compilations and queued tasks
  ai                    AI-powered thread dump analysis using LLM
  list                  List running JVM processes (excluding this tool)
  processes             Detect other processes running on the system that consume high CPU time
  jvm-support           Check whether the target JVM is likely still supported (based on java.version.date)
  help                  Show help (same as --help)
```
<!-- END help -->

---

## `list`

<!-- BEGIN help_list -->
```
Usage: jstall list [-hV] [--no-truncate] [<filters>...]
List running JVM processes (excluding this tool)
      [<filters>...]
                    Optional filter(s) - only show JVMs whose main class
                    contains any of these texts
  -h, --help        Show this help message and exit.
      --no-truncate Don't truncate descriptors in the output
  -V, --version     Print version information and exit.
```
<!-- END help_list -->

**Exit codes:** `0` = JVMs found, `1` = no JVMs found

---

## `status` (default)

This is the **best first command to run** when you want to understand what a JVM is doing.
It combines one short sampling window into a single report covering deadlocks, hot threads,
full thread tables, lock dependencies, GC/heap details, metaspace, VM.vitals (if available),
other busy OS processes, and JVM support status.

Requires at least 2 thread dumps (collected automatically from live JVMs, or pass multiple dump files).

<!-- BEGIN help_status -->
```
Usage: jstall status [-hV] [--dump-count=<count>] [--interval=<interval>]
                     [--keep] [--intelligent-filter] [--full] [--live]
                     [--keep-samples=<keepSamples>] [--file=<replayFile>]
                     [--color] [--top=<top>] [--no-native] [<targets>...]
Best first check: summarize JVM health, hot threads, memory, deadlocks, and lock contention
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
      --no-native                 Hide threads without Java stack traces
                                  (typically native/system threads)
      --top=<top>                 How many hottest threads to show in status
                                  tables (default: 3, -1 = all)
  -V, --version                   Print version information and exit.
```
<!-- END help_status -->

**Exit codes:**
- `0` = no issues
- `2` = deadlock detected
- `10` = JVM is totally outdated (> 1 year based on `java.version.date`)

---

## `jvm-support`

Checks whether the target JVM is reasonably up-to-date based on `java.version.date` from `jcmd VM.system_properties`.

<!-- BEGIN help_jvm_support -->
```
Usage: jstall jvm-support [-hV] [--dump-count=<count>] [--interval=<interval>]
                          [--keep] [--intelligent-filter] [--full] [--live]
                          [--keep-samples=<keepSamples>] [--file=<replayFile>]
                          [--color] [<targets>...]
Check whether the target JVM is likely still supported (based on java.version.date)
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_jvm_support -->

**Exit codes:**
- `0` = JVM is supported / only mildly outdated
- `10` = JVM is totally outdated (> 1 year based on `java.version.date`)

---

## `most-work`

Requires at least 2 thread dumps.

<!-- BEGIN help_most_work -->
```
Usage: jstall most-work [-hV] [--dump-count=<count>] [--interval=<interval>]
                        [--keep] [--intelligent-filter] [--full] [--live]
                        [--keep-samples=<keepSamples>] [--file=<replayFile>]
                        [--color] [--top=<top>] [--no-native]
                        [--stack-depth=<stackDepth>] [<targets>...]
Identify threads doing the most work across dumps
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
      --no-native                 Ignore threads without stack traces (typically
                                  native/system threads)
      --stack-depth=<stackDepth>  Stack trace depth to show (default: 10, 0=all,
                                  in intelligent mode: max relevant frames)
      --top=<top>                 Number of top threads to show (default: 3)
  -V, --version                   Print version information and exit.
```
<!-- END help_most_work -->

Shows CPU time, CPU percentage, core utilization, state distribution, and activity categorization for top threads.

---

## `deadlock`

<!-- BEGIN help_deadlock -->
```
Usage: jstall deadlock [-hV] [--dump-count=<count>] [--interval=<interval>]
                       [--keep] [--intelligent-filter] [--full] [--live]
                       [--keep-samples=<keepSamples>] [--file=<replayFile>]
                       [--color] [<targets>...]
Detect JVM-reported thread deadlocks
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_deadlock -->

**Exit codes:** `0` = no deadlock, `2` = deadlock detected

---

## `threads`

Lists all threads sorted by CPU time in a table format.
Requires at least 2 thread dumps.

<!-- BEGIN help_threads -->
```
Usage: jstall threads [-hV] [--dump-count=<count>] [--interval=<interval>]
                      [--keep] [--intelligent-filter] [--full] [--live]
                      [--keep-samples=<keepSamples>] [--file=<replayFile>]
                      [--color] [--top=<top>] [--no-native] [<targets>...]
List all threads sorted by CPU time
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
      --no-native                 Ignore threads without stack traces (typically
                                  native/system threads)
      --top=<top>                 Number of top threads to show (default: -1 for
                                  all)
  -V, --version                   Print version information and exit.
```
<!-- END help_threads -->

Shows thread name, CPU time, CPU %, state distribution, activity categorization, and top stack frame.

---

## `waiting-threads`

Identifies threads waiting on the same lock instance across all dumps with no CPU progress.

<!-- BEGIN help_waiting_threads -->
```
Usage: jstall waiting-threads [-hV] [--dump-count=<count>]
                              [--interval=<interval>] [--keep]
                              [--intelligent-filter] [--full] [--live]
                              [--keep-samples=<keepSamples>]
                              [--file=<replayFile>] [--color] [--no-native]
                              [--stack-depth=<stackDepth>] [<targets>...]
Identify threads waiting without progress (potentially starving)
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
      --no-native                 Ignore threads without stack traces (typically
                                  native/system threads)
      --stack-depth=<stackDepth>  Stack trace depth to show (1=inline, 0=all,
                                  default: 1, in intelligent mode: max relevant
                                  frames)
  -V, --version                   Print version information and exit.
```
<!-- END help_waiting_threads -->

**Detection criteria:** Thread in ALL dumps, WAITING/TIMED_WAITING state, CPU ≤ 0.0001s, same lock instance.

Highlights lock contention when multiple threads are blocked on the same lock.

---

## `dependency-graph`

Shows thread dependencies by visualizing which threads wait on locks held by other threads.

<!-- BEGIN help_dependency_graph -->
```
Usage: jstall dependency-graph [-hV] [--dump-count=<count>]
                               [--interval=<interval>] [--keep]
                               [--intelligent-filter] [--full] [--live]
                               [--keep-samples=<keepSamples>]
                               [--file=<replayFile>] [--color] [<targets>...]
Show thread dependencies
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
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

## `dependency-tree`

Shows non-deadlock thread dependencies by visualizing which threads wait on locks held by other threads
over time.

<!-- BEGIN help_dependency_tree -->
```
Usage: jstall dependency-tree [-hV] [--dump-count=<count>]
                              [--interval=<interval>] [--keep]
                              [--intelligent-filter] [--full] [--live]
                              [--keep-samples=<keepSamples>]
                              [--file=<replayFile>] [--color] [<targets>...]
Show non deadlock thread dependencies over time
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_dependency_tree -->

---

## `compiler-queue`

Analyzes JIT compiler queue state over time using `jcmd Compiler.queue`. Shows active compilations and queued compilation tasks across multiple samples.

<!-- BEGIN help_compiler_queue -->
```
Usage: jstall compiler-queue [-hV] [--dump-count=<count>]
                             [--interval=<interval>] [--keep]
                             [--intelligent-filter] [--full] [--live]
                             [--keep-samples=<keepSamples>]
                             [--file=<replayFile>] [--color] [<targets>...]
Analyze compiler queue state showing active compilations and queued tasks
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_compiler_queue -->

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

**Notes:**
- Requires JVM support for `jcmd Compiler.queue` (HotSpot/OpenJDK/SapMachine)
- Informational output only (no warning thresholds)
- Included in `status` command output

**Exit codes:** `0` = success (informational only)

---

## `processes`

Checks whether there are any processes running on the system that take a high amount of CPU.
Helpful to identify e.g. a virus scanner or other interfering processes that use more than 20% of the available CPU-time.

<!-- BEGIN help_processes -->
```
Usage: jstall processes [-hV] [--dump-count=<count>] [--interval=<interval>]
                        [--keep] [--intelligent-filter] [--full] [--live]
                        [--keep-samples=<keepSamples>] [--file=<replayFile>]
                        [--color] [<targets>...]
Detect other processes running on the system that consume high CPU time
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_processes -->

---

## `flame`

Generates a flamegraph using async-profiler.

<!-- BEGIN help_flame -->
```
Usage: jstall flame [-hV] [--output=<outputFile>] [--duration=<duration>]
                    [--event=<event>] [--interval=<interval>] [--open]
                    [<target>]
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

---

## `record`

<!-- BEGIN help_record -->
```
Usage: jstall record [-hV] [COMMAND]
Record all data into a zip for later analysis
  -h, --help       Show this help message and exit.
  -V, --version    Print version information and exit.
Commands:
  create   Record all data into a zip for later analysis
  extract  Extract recording folder from ZIP into a folder
  summary  Print the README summary from a recording ZIP
```
<!-- END help_record -->

---

## `vm-vitals`

Use this when the target runs **SapMachine** and you want recent JVM/process/system vitals,
their trends, and the samples that hit recent minima or maxima.

If VM.vitals is unavailable on the target JVM, jstall explains that and points you to
`status`, `gc-heap-info`, and `vm-metaspace` instead.

<!-- BEGIN help_vm_vitals -->
```
Usage: jstall vm-vitals [-hV] [--dump-count=<count>] [--interval=<interval>]
                        [--keep] [--intelligent-filter] [--full] [--live]
                        [--keep-samples=<keepSamples>] [--file=<replayFile>]
                        [--color] [--top=<top>] [<targets>...]
SapMachine-only: show recent JVM/process/system vitals, trends, and extremes
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
      --top=<top>                 Number of recent VM.vitals samples to show
                                  (default: 5, -1 = all)
  -V, --version                   Print version information and exit.
```
<!-- END help_vm_vitals -->

### Output sections

After collecting samples `vm-vitals` prints three sections:

1. **Legend** — human-readable description of every column present in the output.
   Columns tagged `[delta]` (e.g. `cr`, `ld`, `uld`) show the change since the previous sample.
2. **Recent samples table** — the last N samples (controlled by `--top`, default 5), newest last.
3. **Trends** — one row per non-delta column showing the first and last value across the full
   window plus a direction indicator:
   - `→` stable (no net change)
   - `↑ +X` / `↓ -X` monotonically growing or shrinking, with net delta
   - `~ (range: min – max)` oscillating (e.g. heap-used bouncing with GC cycles)
4. **Observations** — automatic signals fired when a threshold is crossed (only shown when at
   least one fires).

### Observations signals and thresholds

| Signal | Threshold |
|---|---|
| High memory pressure | heap-used > 80 % of heap-committed |
| Possible allocation pressure | heap-used growing > 5 % |
| Possible class leak | metaspace-used has any positive net delta |
| Near metaspace expansion trigger | metaspace-used > 75 % of GC threshold |
| Possible thread leak | thread count grew ≥ 5 |
| Possible classloader leak | class count grew ≥ 50 |
| Host over-committed | CPU steal > 10 % |
| OS-level memory pressure | swap growing |
| Possible native memory leak | RSS growing |

### Example output

```
VM.vitals legend (filtered by active columns shown below):

      heap-comm: Java Heap Size, committed
      heap-used: Java Heap Size, used
      meta-comm: Meta Space Size (class+nonclass), committed
      meta-used: Meta Space Size (class+nonclass), used
       meta-csc: Class Space Size, committed
       meta-csu: Class Space Size, used
      meta-gctr: GC threshold
           code: Code cache, committed
       jthr-num: Number of java threads
        jthr-nd: Number of non-demon java threads
        jthr-cr: Threads created [delta]
       cldg-num: Classloader Data
      cldg-anon: Anonymous CLD
        cls-num: Classes (instance + array)
         cls-ld: Class loaded [delta]
        cls-uld: Classes unloaded [delta]

  [delta]: values refer to the previous measurement.

Last 60 minutes (showing 3 of 361 samples, newest last):
                      ----------------------------------jvm-----------------------------------
                      --heap--- ----------meta----------      --jthr--- --cldg-- -----cls-----
                      comm used comm used csc  csu  gctr code num nd cr num anon num  ld   uld 
2026-10-08 13:20:47    1.6g  905m  704k  572k  128k  40k  21m  7m  15  5  0  10  7  876  0  0
2026-10-08 13:50:22    1.6g  210m    2m    2m  384k 341k  21m  7m  16  5  0  28 25 1540  0  0
2026-10-08 14:19:03    1.6g   73m    6m    6m  896k 815k  21m  7m  17  5  0  55 52 2416  0  0

Trends (361 samples, 13:20 → 14:19):
  heap-comm    1.60 GB →   1.60 GB  →
  heap-used  477.00 MB →  73.00 MB  ~ (range: 9.00 MB – 988.00 MB)
  meta-comm  704.00 KB →   6.00 MB  ↑ +5.31 MB
  meta-used  572.00 KB →   6.00 MB  ↑ +5.44 MB
  meta-csc   128.00 KB → 896.00 KB  ↑ +768.00 KB
  meta-csu    40.00 KB → 815.00 KB  ↑ +775.00 KB
  meta-gctr   21.00 MB →  21.00 MB  →
  code         7.00 MB →   7.00 MB  →
  jthr-num          15 →        17  ~ (range: 15 – 21)
  jthr-nd            5 →         5  →
  cldg-num          10 →        55  ↑ +45
  cldg-anon          7 →        52  ↑ +45
  cls-num          876 →      2416  ↑ +1540

Observations:
  * metaspace growing ↑ 5.44 MB over window — possible class leak
  * loaded class count grew +1540 (from 876 to 2416) — possible classloader leak
```

---

## `gc-heap-info`

Shows GC.heap_info last absolute values and change between samples.

<!-- BEGIN help_gc_heap_info -->
```
Usage: jstall gc-heap-info [-hV] [--dump-count=<count>] [--interval=<interval>]
                           [--keep] [--intelligent-filter] [--full] [--live]
                           [--keep-samples=<keepSamples>] [--file=<replayFile>]
                           [--color] [<targets>...]
Show GC.heap_info last absolute values and change
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_gc_heap_info -->

---

## `vm-classloader-stats`

Shows VM.classloader_stats grouped by classloader type.

<!-- BEGIN help_vm_classloader_stats -->
```
Usage: jstall vm-classloader-stats [-hV] [--dump-count=<count>]
                                   [--interval=<interval>] [--keep]
                                   [--intelligent-filter] [--full] [--live]
                                   [--keep-samples=<keepSamples>]
                                   [--file=<replayFile>] [--color]
                                   [<targets>...]
Show VM.classloader_stats grouped by classloader type
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_vm_classloader_stats -->

---

## `vm-metaspace`

Shows VM.metaspace summary and trend.

<!-- BEGIN help_vm_metaspace -->
```
Usage: jstall vm-metaspace [-hV] [--dump-count=<count>] [--interval=<interval>]
                           [--keep] [--intelligent-filter] [--full] [--live]
                           [--keep-samples=<keepSamples>] [--file=<replayFile>]
                           [--color] [<targets>...]
Show VM.metaspace summary and trend
      [<targets>...]              PID, 'all', filter or dump files (or replay
                                  ZIP as first argument)
      --color                     Enable colored output in live mode
      --dump-count=<count>        Number of dumps to collect, default is none
  -f, --file=<replayFile>         Replay ZIP file to analyze (works before or
                                  after subcommand)
      --full                      Run all analyses including expensive ones
                                  (only for status command)
  -h, --help                      Show this help message and exit.
      --intelligent-filter        Use intelligent stack trace filtering
                                  (collapses internal frames, focuses on
                                  application code)
      --interval=<interval>       Interval between dumps, default is 5s
      --keep                      Persist dumps to disk
      --keep-samples=<keepSamples>
                                  Number of last samples to persist as recording
                                  ZIP on quit of live mode (0 = don't persist),
                                  default is 0
  -l, --live                      Live mode: repeatedly collect and display,
                                  like watch (Linux/macOS only)
  -V, --version                   Print version information and exit.
```
<!-- END help_vm_metaspace -->
