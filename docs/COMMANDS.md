# JStall Command Reference

Full CLI help output for all JStall commands. For a quick overview, see the [README](../README.md#commands).

## Global Options

<!-- BEGIN help -->
```bash
Usage: jstall [-hV] [--file=<replayFile>] [--ssh=<sshCommandPrefix>]
              [--cf=<cfAppName>] [--verbose] [COMMAND]
One-shot JVM inspection tool
      --cf=<cfAppName>            Use Cloud Foundry CLI for remote execution
                                  (shortcut for --ssh 'cf ssh <app-name> -c'),
                                  only Linux/Mac support on remote
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
  status                Run multiple analyzers over thread dumps (default command)
  deadlock              Detect JVM-reported thread deadlocks
  most-work             Identify threads doing the most work across dumps
  flame                 Generate a flamegraph of the application using async-profiler
  threads               List all threads sorted by CPU time
  waiting-threads       Identify threads waiting without progress (potentially starving)
  dependency-graph      Show thread dependencies
  dependency-tree       Show non deadlock thread dependencies over time
  vm-vitals             Show VM.vitals (if available)
  gc-heap-info          Show GC.heap_info last absolute values and change
  vm-classloader-stats  Show VM.classloader_stats grouped by classloader type
  vm-metaspace          Show VM.metaspace summary and trend
  compiler-queue        Analyze compiler queue state showing active compilations and queued tasks
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

Runs multiple analyzers (deadlock, most-work, threads, dependency-graph, dependency-tree) over shared thread dumps.
Requires at least 2 thread dumps (collected automatically from live JVMs, or pass multiple dump files).

<!-- BEGIN help_status -->
```
Usage: jstall status [-hV] [--dump-count=<count>] [--interval=<interval>]
                     [--keep] [--intelligent-filter] [--full] [--live]
                     [--keep-samples=<keepSamples>] [--file=<replayFile>]
                     [--color] [--top=<top>] [--no-native] [<targets>...]
Run multiple analyzers over thread dumps (default command)
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
      --top=<top>                 Number of top threads (default: 3)
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

Shows VM.vitals output (if available on the target JVM, e.g. SapMachine).

<!-- BEGIN help_vm_vitals -->
```
Usage: jstall vm-vitals [-hV] [--dump-count=<count>] [--interval=<interval>]
                        [--keep] [--intelligent-filter] [--full] [--live]
                        [--keep-samples=<keepSamples>] [--file=<replayFile>]
                        [--color] [--top=<top>] [<targets>...]
Show VM.vitals (if available)
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
      --top=<top>                 Number of VM.vitals rows to show (default: 5)
  -V, --version                   Print version information and exit.
```
<!-- END help_vm_vitals -->

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
