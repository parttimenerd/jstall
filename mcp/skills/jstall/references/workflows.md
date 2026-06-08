# JStall Investigation Workflows

Detailed runbooks for common JVM diagnostic scenarios. All commands use `jstall_run(args)` for local JVMs or `jstall_remote({type, target, args})` for remote.

## Deadlock Investigation

**Symptoms**: Application hangs, requests time out, threads stuck forever.

```
Step 1: Confirm with status
  jstall_run(["status", "--intelligent-filter", "--no-native", "<pid>"])
  → Look for "deadlock" anywhere in output
  → Note thread names and lock addresses in dependency-tree section

Step 2: Get the full deadlock chain
  jstall_run(["deadlock", "<pid>"])
  → Thread-A holds lock 0x... (java.util.concurrent.locks.ReentrantLock)
      waiting to acquire lock 0x... held by Thread-B
    Thread-B holds lock 0x...
      waiting to acquire lock 0x... held by Thread-A

Step 3: Visualise the lock graph
  jstall_run(["dependency-graph", "--intelligent-filter", "<pid>"])
  → ASCII graph — cycle = deadlock

Step 4: Get full stacks for involved threads
  jstall_run(["threads", "--no-native", "<pid>"])
  → Find the exact source lines where each thread acquires its first lock
```

**Root cause patterns:**
- Lock ordering violation: Thread A acquires lock1 then lock2; Thread B acquires lock2 then lock1. Fix: establish a consistent global lock ordering.
- Nested `synchronized` calls into foreign code while holding a lock. Fix: don't call external methods under a lock.
- Re-entrant lock misuse. Fix: use `ReentrantLock.tryLock(timeout)`.

---

## High CPU Investigation

**Symptoms**: JVM using 100%+ CPU, application is slow but not stuck.

```
Step 1: Identify hot threads
  jstall_run(["most-work", "--top=5", "--intelligent-filter", "<pid>"])
  → Shows threads by CPU time; RUNNABLE with high CPU% = hot path

Step 2: Escalate if stacks aren't clear
  jstall_run(["threads", "--no-native", "--intelligent-filter", "<pid>"])
  → Full stacks for all threads sorted by CPU time

Step 3: Profile with flamegraph
  jstall_run(["flame", "--output=/tmp/flame-<pid>.html", "--duration=20s", "<pid>"])
  → Blocks ~20s; tell user to open HTML in browser
  → Wide frames = hot paths; look for unexpected width in app code
```

**Common causes:**
- HashMap with poor `hashCode` → many hash collisions, long bucket traversals
- Regex catastrophic backtracking: `java.util.regex.*` in hot path
- Logging at DEBUG level in production
- JIT deoptimization: look for `Interpreter` frames in flamegraph
- String.intern() abuse in hot path

---

## I/O / External Wait Investigation

**Symptoms**: Application is slow, but `most-work` shows 0% CPU and all threads WAITING.

The bottleneck is outside the JVM — network, DB, filesystem, or a downstream service.

```
Step 1: Find what threads are waiting on
  jstall_run(["waiting-threads", "--intelligent-filter", "<pid>"])
  → Look for socket read, JDBC, HTTP client frames

Step 2: Scan all thread stacks
  jstall_run(["threads", "--no-native", "--intelligent-filter", "<pid>"])
  → Search for: SocketInputStream.read, Statement.execute,
    HttpClient.send, URLConnection.getInputStream

Step 3: Wall-clock flamegraph (captures blocked time, not just CPU)
  jstall_run(["flame", "--event=wall", "--output=/tmp/wall-<pid>.html",
              "--duration=20s", "<pid>"])
  → Shows where time is spent including I/O waits
  → Wide I/O frames = where the bottleneck is
```

---

## GC Pressure / Memory Investigation

**Symptoms**: Frequent GC pauses, OutOfMemoryError, high allocation rate, slow throughput.

```
Step 1: Check heap and allocation trends
  jstall_run(["gc-heap-info", "<pid>"])
  → Heap used% > 80% → GC pressure
  → Δ growing rapidly → allocation-driven GC

Step 2: Allocation flamegraph
  jstall_run(["flame", "--event=alloc", "--output=/tmp/alloc-<pid>.html",
              "--duration=15s", "<pid>"])
  → Shows which call paths allocate the most
  → Look for: StringBuilder in loops, unnecessary boxing, large temp arrays

Step 3: Deep heap detail (only if steps 1-2 don't surface the cause)
  jstall_run(["status", "--full", "--no-native", "<pid>"])
  → Includes class histogram — can be very large output
```

**Common fixes:** object pooling, primitive arrays instead of boxed collections, `WeakReference` for caches, review log statement construction at DEBUG level.

---

## Classloader Leak Investigation

**Symptoms**: Metaspace growing steadily, eventual `OutOfMemoryError: Metaspace`.

```
Step 1: Confirm growing trend
  jstall_run(["vm-metaspace", "<pid>"])
  → ↑ trend on Non-Class used = classloader leak

Step 2: Identify the leaking classloader type
  jstall_run(["vm-classloader-stats", "<pid>"])
  → Large or growing class counts in custom/framework loaders = leak suspect
  → Standard loaders (AppClassLoader, boot) growing is normal at startup only
```

**Common causes:** OSGi bundles not unloaded, scripting engine classloaders (Groovy, JRuby), application hot-reload frameworks, ClassLoader retained by a static field or thread-local.

---

## Thread Starvation / Stuck Threads

**Symptoms**: Some requests never complete, thread pool exhausted, queue grows unbounded.

```
Step 1: Confirm stuck (same frame across multiple dumps)
  jstall_run(["status", "--intelligent-filter", "--no-native",
              "--dump-count=3", "--interval=5s", "<pid>"])
  → Same thread in most-work across all 3 dumps with no CPU progress
    and identical stack frames = truly stuck, not just slow

Step 2: Identify what they're waiting on
  jstall_run(["waiting-threads", "--intelligent-filter", "<pid>"])

Step 3: Inspect lock graph
  jstall_run(["dependency-graph", "--intelligent-filter", "<pid>"])
  → Snapshot of current lock ownership

  jstall_run(["dependency-tree", "--intelligent-filter", "--dump-count=3", "<pid>"])
  → Lock dependencies over time — catches intermittent contention
```

**Common causes:** thread pool exhaustion (all threads waiting on same slow resource), no timeout on downstream calls (DB, HTTP), blocking call on event-loop thread.

---

## Production Recording Workflow

Use when you need to capture diagnostics without staying connected, or to share with someone else.

```
Step 1: Capture (lightweight, ~20s)
  jstall_run(["record", "create", "--count=3", "--interval=10s",
              "--output=/tmp/rec.zip", "<pid>"])

  For full diagnostics (flamegraph + JFR + class histogram):
  jstall_run(["record", "create", "--full", "--count=3", "--interval=10s",
              "--output=/tmp/rec-full.zip", "<pid>"])

Step 2: Inspect the ZIP
  jstall_run(["record", "summary", "/tmp/rec.zip"])
  → Shows creation time, PIDs, data types captured

Step 3: Analyze offline (same commands as live, ZIP as target)
  jstall_run(["status", "--intelligent-filter", "--no-native", "/tmp/rec.zip"])
  jstall_run(["flame", "--output=/tmp/flame.html", "/tmp/rec.zip"])
  jstall_run(["dependency-graph", "--intelligent-filter", "/tmp/rec.zip"])
```

---

## Environment / Health Check

Use when investigating incidents on infrastructure of unknown quality.

```
jstall_run(["jvm-support", "<pid>"])
→ Reports if JVM is past end-of-life — old JVMs may have known bugs

jstall_run(["gc-heap-info", "<pid>"])
→ Current heap usage and GC region breakdown with Δ trends

jstall_run(["compiler-queue", "<pid>"])
→ JIT queue depth — large sustained queue = compilation bottleneck or cold start

jstall_run(["vm-metaspace", "<pid>"])
→ Metaspace used/committed/reserved with trend

jstall_run(["vm-vitals", "<pid>"])
→ SapMachine only — comprehensive JVM perf counters
```
