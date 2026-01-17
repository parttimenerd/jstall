# Thread Activity Categorization and Dependency Graph Analysis - Implementation Summary

## Overview
Implemented a comprehensive thread activity categorization system and dependency graph analyzer for jstall.

## Features Implemented

### 1. Thread Activity Categorizer (`ThreadActivityCategorizer`)

A smart categorization system that analyzes thread stack traces to determine what kind of work threads are performing.

#### Categories:
- **Network Read** - Socket reads, accept operations, Unix domain sockets
- **Network Write** - Socket writes
- **Network** - General networking (selectors, polling, Netty, WatchService)
- **I/O Read** - File input operations
- **I/O Write** - File output operations  
- **I/O** - General file I/O operations
- **Database** - JDBC and SQL operations
- **External Process** - Process handling, waiting on external processes
- **Lock Wait** - Threads waiting on locks/monitors
- **Sleep** - Thread.sleep() calls
- **Park** - LockSupport.park() calls
- **Computation** - Active computation (RUNNABLE with no specific I/O)
- **Unknown** - Unrecognized activity

#### How It Works:
- Examines up to 5 stack frames deep to find categorizable operations
- Rules are ordered by specificity (most specific first)
- Returns the first matching category found
- Falls back to thread state (RUNNABLE → Computation)

#### Examples:
```java
// Network activity (KQueue selector)
[Network] Netty Station Client 1-1
  at sun.nio.ch.KQueue.poll(Native Method)
  at sun.nio.ch.KQueueSelectorImpl.doSelect(...)

// I/O Read
[I/O Read] BaseDataReader: output stream of fsnotifier
  at java.io.FileInputStream.readBytes(Native Method)
  at java.io.FileInputStream.read(...)

// External Process
[External Process] process reaper (pid 2881)
  at java.lang.ProcessHandleImpl.waitForProcessExit0(Native Method)

// Network Read (Unix domain socket)
[Network Read] External Command Listener
  at sun.nio.ch.UnixDomainSockets.accept0(Native Method)
```

### 2. Integration with Existing Analyzers

#### MostWorkAnalyzer
- Added `Activity:` line showing thread activity distribution
- Example output:
  ```
  1. netty-worker-1
     CPU time: 10.50s (45.0% of total)
     Core utilization: 10.5%
     States: RUNNABLE: 100.0%
     Activity: Network
     Common stack prefix:
       sun.nio.ch.KQueue.poll(Native Method)
       ...
  ```

#### ThreadsAnalyzer
- Added `ACTIVITY` column to the threads table
- Shows activity categorization alongside thread state
- Example:
  ```
  THREAD            CPU TIME  CPU %  STATES    ACTIVITY      TOP STACK FRAME
  io-worker         2.50s     15.0%  RUNNABLE  I/O Read      java.io.FileInputStream.read
  netty-worker      5.00s     30.0%  RUNNABLE  Network       sun.nio.ch.KQueue.poll
  db-connection     8.00s     48.0%  RUNNABLE  Database      java.sql.Connection.executeQuery
  ```

### 3. Dependency Graph Analyzer (`DependencyGraphAnalyzer`)

A new analyzer that visualizes thread dependencies by showing which threads wait on locks held by other threads.

#### Features:
- Shows thread wait relationships with activity categories
- Displays thread states and CPU times
- Detects dependency chains
- Provides summary statistics

#### Example Output:
```
Thread Dependency Graph
======================

Shows which threads are waiting on locks held by other threads.
Format: [Category] Thread Name → [Category] Owner Thread Name (lock: <lockId>)

[I/O Write] file-writer
  → [Network] netty-worker-1 (lock: <0xBBBB>)
     Waiter state: BLOCKED, CPU: 2.10s
     Owner state:  BLOCKED, CPU: 5.20s

[Network] netty-worker-1
  → [Computation] compute-worker (lock: <0xCCCC>)
     Waiter state: BLOCKED, CPU: 5.20s
     Owner state:  RUNNABLE, CPU: 10.50s

[Database] jdbc-connection-pool
  → [I/O Write] file-writer (lock: <0xAAAA>)
     Waiter state: BLOCKED, CPU: 15.70s
     Owner state:  BLOCKED, CPU: 2.10s

Summary:
--------
Total waiting threads: 3
Total dependencies: 3

Dependency Chains Detected:
---------------------------
Threads involved in chains: 3

Chain: [Database] jdbc-connection-pool → [I/O Write] file-writer → [Network] netty-worker-1 → [Computation] compute-worker
```

### 4. CLI Command (`DependencyGraphCommand`)

Added new `dependency-graph` command to jstall CLI:

```bash
jstall dependency-graph <pid|file> [options]
```

#### Options:
- `--dumps N` - Number of dumps to collect (default: 2, uses latest)
- `--interval DURATION` - Interval between dumps (default: 5s)
- `--keep` - Persist dumps to disk

#### Usage Examples:
```bash
# Analyze running JVM
jstall dependency-graph 12345

# Analyze thread dump file
jstall dependency-graph threaddump.txt

# Multiple dumps
jstall dependency-graph 12345 --dumps 5 --interval 2s
```

## Files Created/Modified

### New Files:
1. `src/main/java/me/bechberger/jstall/analyzer/ThreadActivityCategorizer.java`
2. `src/main/java/me/bechberger/jstall/analyzer/impl/DependencyGraphAnalyzer.java`
3. `src/main/java/me/bechberger/jstall/cli/DependencyGraphCommand.java`
4. `src/test/java/me/bechberger/jstall/analyzer/ThreadActivityCategorizerTest.java`
5. `src/test/java/me/bechberger/jstall/analyzer/ThreadActivityCategorizerIntegrationTest.java`
6. `src/test/java/me/bechberger/jstall/analyzer/impl/DependencyGraphAnalyzerTest.java`
7. `src/test/java/me/bechberger/jstall/analyzer/impl/ThreadsAnalyzerTest.java`

### Modified Files:
1. `src/main/java/me/bechberger/jstall/analyzer/impl/MostWorkAnalyzer.java` - Added activity categorization
2. `src/main/java/me/bechberger/jstall/analyzer/impl/ThreadsAnalyzer.java` - Added ACTIVITY column
3. `src/main/java/me/bechberger/jstall/Main.java` - Registered dependency-graph command
4. `src/test/java/me/bechberger/jstall/analyzer/impl/MostWorkAnalyzerTest.java` - Added tests

## Testing

All features are fully tested with comprehensive unit and integration tests:

- **ThreadActivityCategorizer**: 15+ test cases covering all categories
- **DependencyGraphAnalyzer**: 7 test cases covering various scenarios
- **Integration tests**: Real-world thread dump examples
- All existing tests continue to pass

## Benefits

1. **Better Understanding**: Quickly identify what threads are doing (I/O, Network, DB, etc.)
2. **Performance Analysis**: See which categories consume most CPU time
3. **Dependency Visualization**: Understand thread wait relationships and potential bottlenecks
4. **Chain Detection**: Identify complex dependency chains that could lead to performance issues
5. **Extensible**: Easy to add new categories by adding rules to ThreadActivityCategorizer

## Use Cases

- **Performance Debugging**: Identify if performance issues are due to I/O, Network, DB, or computation
- **Deadlock Prevention**: Visualize lock dependencies before they become deadlocks
- **Resource Contention**: See which threads compete for the same locks
- **Architecture Review**: Understand thread activity patterns in production systems