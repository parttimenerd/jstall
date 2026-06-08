---
name: jstall
description: This skill should be used when the user asks about "JVM performance", "Java thread dump", "Java deadlock", "JVM diagnosis", "what is my Java application doing", "slow Java", "Java profiling", "flamegraph", "Java CPU usage", "thread contention", "JVM inspection", "jstall", or any question about analyzing, debugging, or profiling a running Java application.
version: 0.7.1
---

# JStall JVM Diagnostics

Three MCP tools: `jstall_run` for local JVMs, `jstall_remote` for SSH/CF remotes, `jstall_help` to look up command flags on demand.

## Targets

Most commands accept a **target** as the last argument(s):

- `<pid>` — numeric process ID from `list`
- `all` — every running JVM in parallel (output prefixed with `====== PID ... ======`)
- `<filter>` — substring of the main class name (e.g. `MyApp`, `spring-boot`)
- `<zip>` — path to a recording ZIP for offline analysis

## Standard workflow

```
Step 1 — discover (skip if the user already provided a PID, filter, or ZIP path)
jstall_run(["list"])
→ <pid> <main-class-or-jar>
   25409 spring-boot-language-server.jar
   83465 org.eclipse.equinox.launcher.jar

Tip: jstall_run(["list", "MyApp"]) filters by main class substring.

Step 2 — diagnose
jstall_run(["status", "--intelligent-filter", "--no-native", "<pid>"])
→ sections: uptime · gc-heap-info · vm-classloader-stats · vm-metaspace ·
            compiler-queue · most-work · threads · dependency-tree · jvm-support

Step 3 — triage the output (see decision tree below)
```

The `status` output contains everything for initial triage. Only call further commands when you need more detail. Avoid `--full` until standard `status` has been reviewed — it adds class histogram and heap detail that can produce very large output.

If unsure which flags a command accepts, call `jstall_help({command: "<name>"})` first. For subcommands use a space: `jstall_help({command: "record create"})`.

For remote JVMs replace `jstall_run` with `jstall_remote({type, target, args})` throughout.

---

## Triage decision tree

After reading `status` output, pick the branch that matches:

```
most-work shows threads with high CPU% and RUNNABLE
  → High CPU scenario

most-work shows threads with BLOCKED state
  → Lock contention scenario

most-work shows 0% CPU / all WAITING or TIMED_WAITING, app still slow
  → I/O or external wait scenario

dependency-tree has entries  OR  threads section shows BLOCKED
  → Lock contention scenario

status output contains "deadlock"
  → Deadlock scenario

gc-heap-info: Heap used% > 80%  OR  Δ growing fast
  → GC pressure / memory scenario

vm-metaspace: trend shows ↑ growing  OR  vm-classloader-stats shows growing custom loader
  → Classloader leak scenario

compiler-queue: Active compilations > 0 sustained across samples
  → JIT backlog — app may be slow at startup; usually self-resolving

jvm-support: "outdated" warning
  → Recommend JVM update; note which vendor/version and how old
```

---

## Scenarios

### Deadlock

`status` mentions deadlock in its output. Escalate:

```
jstall_run(["deadlock", "<pid>"])
→ Thread-A holds lock 0x... (java.util.concurrent.locks.ReentrantLock)
    waiting to acquire lock 0x... held by Thread-B
  Thread-B holds lock 0x...
    waiting to acquire lock 0x... held by Thread-A

jstall_run(["dependency-graph", "--intelligent-filter", "<pid>"])
→ ASCII graph: Thread-A → lock X → Thread-B → lock Y → Thread-A
```

Fix: establish consistent lock-acquisition ordering across all call sites, or use `tryLock(timeout)`.

### High CPU

```
jstall_run(["most-work", "--top=5", "--intelligent-filter", "<pid>"])
→ 1. worker-1   CPU time: 4.82s  Core utilization: 96.4%  RUNNABLE
     com.example.HotMethod.compute(HotMethod.java:42)

jstall_run(["threads", "--no-native", "--top=10", "<pid>"])
→ full thread table sorted by CPU time — use when most-work stacks aren't deep enough

jstall_run(["flame", "--output=/tmp/flame-<pid>.html", "--duration=15s", "<pid>"])
→ blocks ~15s, then saves flamegraph HTML — tell user to open in browser
```

Event options for `flame`: `cpu` (default), `alloc`, `lock`, `wall`, `itimer`.

### Lock contention (non-deadlock)

```
jstall_run(["waiting-threads", "--intelligent-filter", "<pid>"])
→ threads that are WAITING/TIMED_WAITING without making progress

jstall_run(["dependency-graph", "--intelligent-filter", "<pid>"])
→ current snapshot of lock ownership — use when contention is happening right now

jstall_run(["dependency-tree", "--intelligent-filter", "--dump-count=3", "<pid>"])
→ lock dependencies across 3 dumps — use when contention is intermittent
  and a single snapshot might miss it
```

### I/O or external wait (all threads idle, app still slow)

All `most-work` threads show 0% CPU and WAITING/TIMED_WAITING. The bottleneck is outside the JVM — network, DB, filesystem, or a downstream service.

```
jstall_run(["waiting-threads", "--intelligent-filter", "<pid>"])
→ look for threads stuck in socket read, JDBC, HTTP client, etc.

jstall_run(["threads", "--no-native", "--intelligent-filter", "<pid>"])
→ scan all thread stacks for blocking I/O frames (SocketInputStream.read,
  Statement.execute, HttpClient.send, etc.)

jstall_run(["flame", "--event=wall", "--output=/tmp/wall-<pid>.html",
            "--duration=15s", "<pid>"])
→ wall-clock flamegraph captures time spent waiting, not just CPU —
  shows where threads block on external calls
```

### GC pressure / memory

```
jstall_run(["gc-heap-info", "<pid>"])
→ Heap used 80,256K | 18.8%   Δ +0K   ← Δ growing rapidly = allocation-driven GC

jstall_run(["flame", "--event=alloc", "--output=/tmp/alloc-<pid>.html",
            "--duration=15s", "<pid>"])
→ allocation flamegraph — shows which call paths allocate the most

jstall_run(["status", "--full", "--no-native", "<pid>"])
→ includes heap histogram — only use when lighter status didn't surface the cause;
  output can be very large
```

### Classloader leak

```
jstall_run(["vm-metaspace", "<pid>"])
→ VM.metaspace (2 samples):
   Non-Class: 44.20 MB used  ↑ +10.24 KB used  ← growing trend = leak suspect
   Class:      6.48 MB used  → +0 bytes used

jstall_run(["vm-classloader-stats", "<pid>"])
→ Classes per classloader — large or growing counts in custom loaders = leak
```

Classloader leaks typically come from frameworks that create new classloaders (OSGi, hot-reload, scripting engines) without unloading old ones.

### Environment / health check

```
jstall_run(["jvm-support", "<pid>"])
→ JVM looks outdated based on java.version.date=2026-01-20 (4mo 19d old)
   Detected: Eclipse Adoptium 21.0.10 — Recommendation: update the JVM

jstall_run(["gc-heap-info", "<pid>"])      ← heap + GC details and Δ trends
jstall_run(["vm-metaspace", "<pid>"])      ← metaspace summary + virtual space
jstall_run(["compiler-queue", "<pid>"])    ← JIT queue depth and active compilations
jstall_run(["vm-vitals", "<pid>"])         ← VM.vitals counters (SapMachine only)
```

### Capture for offline analysis or sharing

```
jstall_run(["record", "create", "--count=3", "--interval=10s",
            "--output=/tmp/rec.zip", "<pid>"])
→ 3 samples × 10s apart → /tmp/rec.zip

jstall_run(["record", "summary", "/tmp/rec.zip"])
→ creation time, JVM list, data types captured

jstall_run(["status", "--intelligent-filter", "--no-native", "/tmp/rec.zip"])
jstall_run(["flame", "--output=/tmp/flame.html", "/tmp/rec.zip"])
```

Add `--full` to `record create` to include flamegraph, JFR recording, class histogram, and class hierarchy. Omit for a lightweight ZIP with just thread dumps and heap info.

### High system CPU (not the JVM)

```
jstall_run(["processes", "<pid>"])
→ detects other OS processes consuming high CPU alongside the JVM
  (use when OS reports high CPU but JVM threads all look idle)
```

### Remote (SSH / Cloud Foundry)

```
jstall_remote({type: "ssh", target: "user@host", args: ["list"]})
jstall_remote({type: "ssh", target: "user@host",
               args: ["status", "--intelligent-filter", "--no-native", "<pid>"]})
jstall_remote({type: "cf", target: "my-app", args: ["list"]})
jstall_remote({type: "cf", target: "my-app",
               args: ["status", "--intelligent-filter", "--no-native", "<pid>"]})
```

Remote support requires jstall on the remote host (PATH or jbang). Linux/macOS only on the remote side.

---

## Command reference

```
list                   — list running JVMs (accepts optional filter substrings)
status                 — thread analysis, deadlock detection, heap, metaspace (default)
deadlock               — deadlock-only check
most-work              — threads doing the most work across dumps
flame                  — async-profiler flamegraph → HTML (blocks for --duration, default 10s)
threads                — all threads sorted by CPU time (full stacks, use after most-work)
waiting-threads        — threads waiting without progress (potentially starving)
dependency-graph       — lock dependency snapshot (active/current contention)
dependency-tree        — lock dependencies across multiple dumps (intermittent contention)
record create          — capture diagnostics ZIP; add --full for flamegraph + JFR
record summary         — print README summary from a recording ZIP
record extract         — extract recording ZIP to a folder
gc-heap-info           — GC heap details and Δ trends
vm-metaspace           — metaspace summary and virtual space
vm-classloader-stats   — classloader stats grouped by type (leak detection)
compiler-queue         — JIT queue state and active compilations
vm-vitals              — VM.vitals counters (SapMachine only)
processes              — other OS processes consuming high CPU
jvm-support            — check whether JVM version is still supported
```

Use `jstall_help({command: "<name>"})` for full flag reference. Subcommands use a space: `jstall_help({command: "record create"})`.

**Notes:** Java 17+ required to run jstall (target JVM can be 11+). `flame` blocks for `--duration` (default 10s). `target="all"` runs in parallel across every JVM — output is separated by `====== PID ... ======` headers.
