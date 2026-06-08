# JStall Investigation Workflows

Detailed runbooks for common JVM diagnostic scenarios.

## Deadlock Investigation

**Symptoms**: Application hangs, requests time out, threads stuck forever.

```
Step 1: jstall_status(pid)
  → Look for "DEADLOCK DETECTED" section near top of output
  → Note: thread names, stack frames, lock addresses

Step 2: jstall_dependency_graph(pid)
  → Output format:
      [Category] Thread-A
        -> [Category] Thread-B (lock: <0x1234>)
           Waiter state: BLOCKED, CPU: 0.00s
  → A deadlock = cycle in this graph (A waits for B, B waits for A)

Step 3: Read the stack traces
  → jstall_run(["threads", "--no-native", "<pid>"])
  → Find the frames where Thread-A holds a lock and waits for another

Step 4: Root cause patterns
  → Lock ordering violation: A acquires lock1 then lock2; B acquires lock2 then lock1
    Fix: Establish consistent lock ordering across all code paths
  → Nested synchronized calls into foreign code
    Fix: Don't call external methods while holding a lock
  → tryLock timeout: replace synchronized with ReentrantLock + tryLock(timeout)
```

## High CPU Investigation

**Symptoms**: JVM using 100% CPU, application is slow but not stuck.

```
Step 1: jstall_status(pid, top: 5)
  → "Most Work" section lists threads by CPU time consumed
  → Look for threads with very high CPU%

Step 2: Read hot thread stack traces
  → The status output includes top frames for hot threads
  → Look for: tight loops, hash collisions, regex backtracking, JSON parsing

Step 3: If not clear from status: jstall_flamegraph(pid, durationSeconds: 20, event: "cpu")
  → HTML flamegraph — tell user to open in browser
  → Wide frames = hot paths; look for unexpected width

Step 4: Common causes
  → HashMap with high collision rate (poor hashCode): look for many LinkedList traversals
  → Regex catastrophic backtracking: java.util.regex.*
  → String intern abuse: String.intern() in hot path
  → Log4j/SLF4J: logging at DEBUG level in production
  → JIT deoptimization: look for Interpreter frames in flamegraph
```

## Memory / GC Pressure Investigation

**Symptoms**: Frequent GC pauses, OutOfMemoryError, high allocation rate.

```
Step 1: jstall_status(pid, full: true)
  → Includes "GC Heap Info" showing heap used/committed/max
  → Shows GC pause info if available

Step 2: jstall_run(["gc-heap-info", "<pid>"])
  → More detail on GC type, heap regions, recent GC activity

Step 3: jstall_flamegraph(pid, event: "alloc", durationSeconds: 15)
  → Allocation flamegraph — shows which methods allocate the most
  → Look for: StringBuilder in loops, unnecessary boxing, large arrays

Step 4: jstall_run(["vm-metaspace", "<pid>"])
  → Check if metaspace is growing (class loader leak)

Step 5: Common fixes
  → Object pooling for frequently allocated objects
  → StringBuilder → StringJoiner or direct concatenation
  → Primitive arrays instead of boxed collections
  → WeakReference for caches
```

## Production Recording Workflow

When you need to capture diagnostics without staying connected:

```
Step 1: Record (takes ~10s by default)
  jstall_record(pid: 12345, full: true, count: 3, interval: "10s")
  → Returns ZIP path (e.g. /tmp/jstall-12345-2026-06-08_12-00-00.zip)

Step 2: Retrieve the ZIP from the server (scp, cf files, etc.)

Step 3: Analyze offline
  jstall_status(recordingZip: "/local/path/recording.zip")
  jstall_flamegraph(recordingZip: "/local/path/recording.zip")
  jstall_dependency_graph(recordingZip: "/local/path/recording.zip")
  jstall_run(["record", "summary", "/local/path/recording.zip"])
```

## Thread Starvation / Stuck Threads

**Symptoms**: Some requests never complete, thread pool exhausted.

```
Step 1: jstall_run(["waiting-threads", "<pid>"])
  → Shows threads in WAITING or TIMED_WAITING state that may be stuck
  → Look for threads waiting on a condition that never fires

Step 2: jstall_dependency_graph(pid)
  → Even without a deadlock, shows who is waiting on whom
  → Non-deadlock dependency chains can cause effective starvation

Step 3: jstall_status(pid, dumps: 3, interval: "5s")
  → Collect 3 dumps 5s apart — threads stuck in the same frame across dumps are stuck

Step 4: Common causes
  → Thread pool exhaustion: all threads waiting on same resource
  → Slow downstream (DB, HTTP) with no timeout: threads pile up
  → Event loop starvation: blocking calls on non-blocking thread
```

## JVM Support Check

When investigating incidents on older infrastructure:

```
jstall_run(["jvm-support", "<pid>"])
→ Reports if the JVM version is past end-of-life (> 1 year old)
→ Old JVMs may have known bugs — upgrade recommendation

jstall_run(["compiler-queue", "<pid>"])
→ Shows JIT compiler queue size
→ Very large queue = JVM hasn't warmed up yet, or compilation is bottlenecked
```
