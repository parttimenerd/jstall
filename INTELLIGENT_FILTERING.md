# Intelligent Stack Trace Filtering - Implementation Summary

## Overview
Added intelligent stack trace filtering to jstall that automatically collapses internal/framework code while preserving application code and important system operations.

## Key Features

### 1. Smart Frame Classification

Frames are classified into three categories:

- **Application Code**: User code that should always be shown
- **Important Framework Code**: Critical operations that provide context (I/O, Network, DB, etc.)
- **Internal Code**: Framework internals that can be collapsed

### 2. Important Frame Detection

The following operations are always preserved:

- **I/O Operations**: `java.io.*InputStream.read`, `java.io.*OutputStream.write`, File channels
- **Network Operations**: `java.net.*`, Socket operations, NIO selectors
- **Database Operations**: `java.sql.*`, `javax.sql.*`
- **Threading**: Executors, locks, concurrent utilities
- **HTTP/Web**: Servlet API, Spring Web
- **Process Operations**: `java.lang.Process*`

### 3. Internal Frame Collapsing

The following are automatically collapsed:

- **JDK Internals**: `jdk.internal.*`, `sun.*`, `com.sun.*`
- **Reflection**: `java.lang.reflect.*`, `java.lang.invoke.*`
- **Kotlin Internals**: `kotlin.*internal.*`
- **Framework Internals**: Spring support classes, Tomcat/Catalina internals, Netty internals
- **Generated Code**: Lambda forms, proxies, CGLIB, ByteBuddy

### 4. Output Format

**Without Intelligent Filtering:**
```
Stack:
  at com.example.MyController.handleRequest(MyController.java:42)
  at jdk.internal.reflect.GeneratedMethodAccessor123.invoke(Unknown Source)
  at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
  at java.lang.reflect.Method.invoke(Method.java:566)
  at org.springframework.web.method.support.InvocableHandlerMethod.invoke(InvocableHandlerMethod.java:205)
  at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:150)
  ... (15 more frames)
```

**With Intelligent Filtering:**
```
Stack:
  at com.example.MyController.handleRequest(MyController.java:42)
  ... (3 internal frames omitted)
  at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:150)
  at com.example.Service.processRequest(Service.java:78)
  at java.sql.Connection.executeQuery(Connection.java:100)
  at com.example.Repository.findUser(Repository.java:45)
  ... (5 internal frames omitted)
  at java.io.FileInputStream.read(FileInputStream.java:200)
  at com.example.FileProcessor.readData(FileProcessor.java:120)
```

## Usage

### Command Line

Added `--intelligent-filter` option to commands that display stack traces:

```bash
# Most-work analyzer
jstall most-work <pid> --intelligent-filter --stack-depth 15

# Waiting threads analyzer
jstall waiting-threads <pid> --intelligent-filter --stack-depth 10

# Status command (includes both)
jstall status <pid> --intelligent-filter
```

### Stack Depth Behavior

The `--stack-depth` option works differently with intelligent filtering:

- **Normal Mode**: Shows exactly N frames, then "... (X more frames)"
- **Intelligent Mode**: Shows up to N *relevant* frames (application + important), collapsing internal frames

Example with `--stack-depth 5`:
- Normal: Shows first 5 frames regardless of content
- Intelligent: Shows up to 5 application/important frames, collapsing internals in between

## Implementation Details

### Core Classes

1. **IntelligentStackFilter** (`src/main/java/me/bechberger/jstall/analyzer/IntelligentStackFilter.java`)
   - Pattern-based frame classification
   - Filtering algorithm
   - Formatting utilities

2. **BaseAnalyzer** (`src/main/java/me/bechberger/jstall/analyzer/BaseAnalyzer.java`)
   - Added `getIntelligentFilterOption()` helper
   - Added `formatStackTraceFromFrames()` method with intelligent filtering support

### Integration Points

Updated analyzers:
- **WaitingThreadsAnalyzer**: Uses intelligent filtering for thread stack traces
- **MostWorkAnalyzer**: Supports intelligent filtering (via `supportedOptions`)
- **CLI Commands**: MostWorkCommand, WaitingThreadsCommand

### Algorithm

```
For each stack frame:
  1. Classify as application, important, or internal
  2. If application or important:
     - Flush any accumulated internal frames as "collapsed" marker
     - Add frame to output
     - Increment relevant frame counter
  3. If internal:
     - Accumulate count
  4. If relevant frame limit reached:
     - Add remaining frames as "... (N more frames)"
     - Break
```

## Benefits

1. **Reduced Noise**: Hides framework boilerplate that doesn't help debugging
2. **Better Focus**: Highlights application code and critical system operations
3. **Customizable Depth**: Still respects `--stack-depth` for relevant frames
4. **Context Preservation**: Keeps important I/O, network, and DB operations visible
5. **Debugging Aid**: Makes it easier to spot where threads are really spending time

## Testing

Comprehensive test suite in `IntelligentStackFilterTest`:
- Application code preservation
- Internal frame collapsing
- Important frame detection (I/O, network, DB)
- Max depth enforcement
- Mixed scenarios
- Formatting verification

## Example Use Cases

### 1. Web Application Thread Dump
**Problem**: 200-line stack trace with 150 lines of Spring/Tomcat internals
**Solution**: Intelligent filter shows ~20 lines: your controllers/services + key Spring beans + HTTP operations

### 2. Database-Heavy Application
**Problem**: Hard to see which queries are blocking
**Solution**: Intelligent filter preserves `java.sql.*` frames while collapsing JDBC driver internals

### 3. Async/Reactive Code
**Problem**: Lots of lambda proxy and framework code
**Solution**: Intelligent filter collapses generated lambdas and shows actual business logic

## Configuration

### Extensibility

Easy to add new patterns by editing `IntelligentStackFilter`:

```java
// Add to IMPORTANT_PATTERNS for frames to always show
Pattern.compile("^com\\.mycompany\\.framework\\..*")

// Add to INTERNAL_PATTERNS for frames to collapse
Pattern.compile("^com\\.vendor\\.internal\\..*")
```

### Future Enhancements

Potential improvements:
- User-configurable patterns via config file
- Different filtering modes (aggressive, balanced, minimal)
- Smart grouping of similar collapsed regions
- Detection of recursive call patterns

## Summary

Intelligent stack trace filtering dramatically improves the readability of thread dumps by automatically hiding framework noise while preserving critical information. It's opt-in via `--intelligent-filter` and works seamlessly with existing `--stack-depth` controls.