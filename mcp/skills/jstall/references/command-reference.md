# JStall CLI Command Reference

Quick reference for all jstall commands and flags. Use via `jstall_run(["<command>", ...args])`.

## Global Flags

| Flag | Description |
|---|---|
| `--ssh "ssh user@host"` | Run via SSH (prefix passed verbatim to shell) |
| `--cf <app>` | Run via Cloud Foundry app SSH |
| `-v, --verbose` | Show verbose remote SSH command logging |

## Analysis Commands

### `status` — Primary diagnostic

```
["status", "--intelligent-filter", "--no-native", "<pid>"]
["status", "--intelligent-filter", "--no-native", "/path/recording.zip"]
["status", "--intelligent-filter", "--no-native", "all"]
```

| Flag | Default | Description |
|---|---|---|
| `--intelligent-filter` | off | Collapse framework internals, focus on app code |
| `--no-native` | off | Exclude threads with no Java frames |
| `--full` | off | Add class histogram and expensive diagnostics — large output |
| `--top=N` | 3 | Show top N threads in most-work section |
| `--dump-count=N` | 1 | Number of sequential thread dumps |
| `--interval=T` | 5s | Time between sequential dumps |
| `--keep` | off | Write thread dumps to disk |
| `--live` | — | Interactive TUI mode (not useful via MCP) |

### `deadlock` — Deadlock-only check

```
["deadlock", "<pid>"]
```

Faster than `status` when you only need deadlock detection.

### `most-work` — Top CPU threads

```
["most-work", "--top=5", "--intelligent-filter", "<pid>"]
```

| Flag | Default | Description |
|---|---|---|
| `--top=N` | 3 | Number of threads to show |
| `--intelligent-filter` | off | Collapse framework internals |
| `--no-native` | off | Exclude native-only threads |
| `--stack-depth=N` | 10 | Stack frames to show (0=all) |
| `--dump-count=N` | 1 | Number of dumps to average over |

### `threads` — All threads sorted by CPU time

```
["threads", "--no-native", "--intelligent-filter", "<pid>"]
["threads", "--no-native", "--top=10", "<pid>"]
```

Use after `most-work` when you need full stacks for all threads, not just the top N.

| Flag | Default | Description |
|---|---|---|
| `--no-native` | off | Exclude threads with no Java frames |
| `--intelligent-filter` | off | Collapse framework internals |
| `--top=N` | all | Limit to top N threads |

### `waiting-threads` — Stuck/starving threads

```
["waiting-threads", "--intelligent-filter", "<pid>"]
```

Shows threads in WAITING or TIMED_WAITING that may be stalled without making progress.

### `dependency-graph` — Lock dependency snapshot

```
["dependency-graph", "--intelligent-filter", "<pid>"]
```

Current snapshot of which thread holds which lock and who is waiting. Use when contention is active right now. Deadlock cycles are clearly visible.

### `dependency-tree` — Lock dependencies over time

```
["dependency-tree", "--intelligent-filter", "--dump-count=3", "<pid>"]
```

Shows lock dependencies across multiple dumps. Use when contention is intermittent and a single snapshot might miss it.

### `flame` — Flamegraph

```
["flame", "--output=/tmp/flame-<pid>.html", "--duration=15s", "<pid>"]
["flame", "--event=alloc", "--output=/tmp/alloc-<pid>.html", "--duration=15s", "<pid>"]
["flame", "--output=/tmp/flame.html", "/path/recording.zip"]
```

| Flag | Default | Description |
|---|---|---|
| `--duration=T` | 10s | Profiling duration — tool blocks for this long |
| `--event=E` | cpu | cpu · alloc · lock · wall · itimer |
| `--interval=T` | 10ms | Sampling interval |
| `--output=<path>` | flame.html | Output HTML file path |
| `--open` | off | Auto-open in browser after generation |

Tell the user the output path and to open it in a browser.

## JVM Information Commands

### `gc-heap-info` — Heap and GC information

```
["gc-heap-info", "<pid>"]
```

Heap used/total with Δ trend, GC region breakdown, metaspace summary.

### `vm-metaspace` — Metaspace detail

```
["vm-metaspace", "<pid>"]
```

Non-class and class space: capacity, used, committed, free, trend. Growing trend (`↑`) = classloader leak.

### `vm-classloader-stats` — Classloader statistics

```
["vm-classloader-stats", "<pid>"]
```

Classes loaded per classloader type with trend. Large or growing counts in custom loaders = leak suspect.

### `compiler-queue` — JIT compiler queue

```
["compiler-queue", "<pid>"]
```

Active compilations and queued tasks across samples. Sustained active compilations = JIT backlog.

### `vm-vitals` — JVM performance counters

```
["vm-vitals", "<pid>"]
```

CPU, memory, GC, and other JVM metrics. **SapMachine only** — returns error on other JVMs.

### `jvm-support` — JVM support status

```
["jvm-support", "<pid>"]
```

Checks if JVM version is past end-of-life based on `java.version.date`. Supported JVMs are typically released within the last ~4 months.

## Process Discovery

### `list` — List JVM processes

```
["list"]
["list", "MyApp"]          ← filter by main class substring
["list", "--no-truncate"]  ← show full descriptors
```

### `processes` — Non-JVM high-CPU processes

```
["processes", "<pid>"]
```

Detects other OS processes consuming high CPU alongside the JVM.

## Recording Commands

### `record create` — Capture a recording ZIP

```
["record", "create", "--count=3", "--interval=10s", "--output=/tmp/rec.zip", "<pid>"]
["record", "create", "--full", "--count=3", "--output=/tmp/rec.zip", "all"]
```

| Flag | Default | Description |
|---|---|---|
| `--count=N` / `--dump-count=N` | 2 | Number of samples |
| `--interval=T` | 5s | Time between samples |
| `--output=<path>` | — | Output ZIP path |
| `--full` | off | Include flamegraph, JFR, class histogram, class hierarchy |
| `--force` | off | Overwrite existing ZIP without prompting |
| `--no-parallel` | off | Disable parallel recording when target is `all` |

### `record summary` — Summarize a recording

```
["record", "summary", "/path/recording.zip"]
```

Shows creation time, JVM list, PIDs, and data types captured.

### `record extract` — Extract a recording

```
["record", "extract", "/path/recording.zip"]
```

Extracts ZIP contents to a folder for manual inspection.

## Replay Mode

Analyze a recording ZIP without a live JVM. Pass the ZIP path as the target:

```
["status", "--intelligent-filter", "--no-native", "/path/recording.zip"]
["flame", "--output=/tmp/flame.html", "/path/recording.zip"]
["dependency-graph", "--intelligent-filter", "/path/recording.zip"]
```

## Time Format

Duration arguments accept: `500ms`, `5s`, `1m`, `1h`.
Sampling interval for `flame --interval`: `10ms` or nanoseconds as integer (e.g. `1000000`).
