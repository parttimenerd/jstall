# JStall CLI Command Reference

Quick reference for all jstall commands and flags. Use via `jstall_run(args: [...])`.

## Global Flags

| Flag | Description |
|---|---|
| `-f <zip>` | Replay mode — analyze a recording ZIP instead of a live JVM |
| `--ssh <prefix>` | Run via SSH, e.g. `--ssh "ssh user@host"` |
| `--cf <app>` | Run via Cloud Foundry app SSH |
| `-v, --verbose` | Show verbose remote command logging |

## Analysis Commands

### `status` — Primary diagnostic

```
["status", [flags], <pid|"all">]
["status", [flags], <zip>]       ← replay mode without -f prefix
```

| Flag | Default | Description |
|---|---|---|
| `--full` | false | Include VM vitals, metaspace, compiler queue |
| `--intelligent-filter` | false | Collapse framework internals |
| `--no-native` | false | Exclude threads with no Java frames |
| `--keep` | false | Write thread dumps to disk |
| `--top=N` | 3 | Show top N threads by CPU time |
| `--dumps=N` | 1 | Number of sequential dumps |
| `--interval=T` | 5s | Time between sequential dumps |
| `--live` | — | Interactive TUI mode (not for MCP use) |

### `deadlock` — Deadlock-only check

```
["deadlock", <pid>]
```

Faster than status when you only need deadlock detection.

### `most-work` — Top CPU threads

```
["most-work", "--top=5", <pid>]
```

| Flag | Description |
|---|---|
| `--top=N` | Show top N threads (default: 3) |
| `--no-native` | Exclude native-only threads |

### `threads` — All threads

```
["threads", "--no-native", <pid>]
```

| Flag | Description |
|---|---|
| `--no-native` | Exclude threads with no Java frames |
| `--intelligent-filter` | Collapse framework internals |

### `waiting-threads` — Stuck threads

```
["waiting-threads", <pid>]
```

Shows threads in WAITING or TIMED_WAITING that may be stalled.

### `dependency-graph` — Lock dependency visualization

```
["dependency-graph", "--intelligent-filter", <pid>]
```

Shows thread → lock → thread chains. Deadlock cycles clearly visible.

### `dependency-tree` — Non-deadlock dependencies over time

```
["dependency-tree", <pid>]
```

Shows threads waiting on locks held by others, without requiring a deadlock.

### `flame` — Flamegraph

```
["flame", "-o", "/tmp/out.html", <pid>]
["flame", "--duration=15s", "--event=alloc", "-o", "/tmp/out.html", <pid>]
```

| Flag | Default | Description |
|---|---|---|
| `--duration=T` | 10s | Profiling duration |
| `--event=E` | cpu | Event: cpu, alloc, lock, wall, itimer |
| `--interval=T` | 10ms | Sampling interval |
| `-o <path>` | — | Output HTML file path (required) |

Use `jstall_flamegraph` tool instead — it handles the output path automatically.

## JVM Information Commands

### `vm-vitals` — JVM performance counters

```
["vm-vitals", <pid>]
```

Shows CPU, memory, GC, and other JVM metrics. Requires SapMachine or compatible JVM.

### `gc-heap-info` — Heap and GC information

```
["gc-heap-info", <pid>]
```

Heap usage (used/committed/max), GC type, recent GC activity.

### `vm-classloader-stats` — Classloader statistics

```
["vm-classloader-stats", <pid>]
```

Number of classes loaded per classloader.

### `vm-metaspace` — Metaspace summary

```
["vm-metaspace", <pid>]
```

Metaspace used/committed/max. Growing metaspace = potential classloader leak.

### `compiler-queue` — JIT compiler queue

```
["compiler-queue", <pid>]
```

JIT compilation queue depth. Large queue = JVM warming up or compile bottleneck.

### `jvm-support` — JVM support status

```
["jvm-support", <pid>]
```

Checks if JVM version is past end-of-life (>1 year old since release).

## Process Discovery

### `list` — List JVM processes

```
["list"]
```

Lists all running JVM processes with PIDs and main class names.

### `processes` — Non-JVM high-CPU processes

```
["processes"]
```

Detects CPU-hungry processes that are NOT JVMs.

## Recording Commands

### `record create` — Create a recording

```
["record", "create", "-o", "/path/output.zip", <pid|"all">]
```

| Flag | Default | Description |
|---|---|---|
| `--full` | false | Include expensive diagnostics |
| `--count=N` | 2 | Number of samples |
| `--interval=T` | 5s | Time between samples |
| `-o <path>` | — | Output ZIP path (required) |

### `record summary` — Summarize a recording

```
["record", "summary", "/path/recording.zip"]
```

Shows what's in the ZIP: time range, PIDs, commands captured.

## Replay Mode

Analyze recordings without a live JVM. Two forms:

```
["-f", "<zip>", "status", <pid|"all">]     ← global -f flag
["status", "<zip>"]                          ← zip as positional arg
```

The `jstall_status` and `jstall_flamegraph` tools accept `recordingZip` directly.

## Time Format

Duration arguments accept: `500ms`, `5s`, `1m`, `1h`.
Interval/sampling arguments for flamegraph accept: `10ms`, `1000000` (nanoseconds).
