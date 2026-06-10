# JStall

[![CI](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ci.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/jstall)](https://central.sonatype.com/artifact/me.bechberger/jstall) [![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/30667-jstall.svg)](https://plugins.jetbrains.com/plugin/30667-jstall)

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
* **Record & Replay**: Record diagnostic data for later analysis or sharing as a zip file
* **Minimal Builds Available**: There are minimal builds (< 250 KB) available that exclude the bundled async-profiler ([SapMachine](https://sap.github.io/SapMachine/) ships with it anyway) and are optimized via [femtojar](https://github.com/parttimenerd/femtojar).
* **Remote JVMs**: via `-s "ssh user@host" COMMAND` or via `--cf APP COMMAND` for cloud-foundry
* **Live Mode**: Interactive TUI with `--live` for continuous monitoring with sorting, filtering, and color
* **Top threads filtering**: Use `--top=<n>` to show only the top N threads by CPU time (e.g., `jstall threads --top=5 --live <pid>`)

Requires Java 17+ to run.

You can use the `jstall` CLI tool directly, or through the
[IntelliJ plugin](https://plugins.jetbrains.com/plugin/30667-jstall),
[VSCode extension](https://marketplace.visualstudio.com/items?itemName=bechberger.jstall),
or [Claude Code / GitHub Copilot MCP server](#use-with-claude-code-mcp) (with skill support).

## Installation

Download the latest executable from the [releases page](https://github.com/parttimenerd/jstall/releases).

Or use with [JBang](https://www.jbang.dev/): `jbang jstall@parttimenerd/jstall <pid>`

## Quick Start

**Example:** Find out what your application (in our example `MyApplication` with pid `12345`) is doing right now

```bash
# Quick status check (checks for deadlocks and hot threads)
jstall 12345

# Or explicitly run the status command, that also supports using JVM name filters
jstall status MyApplication

# Find threads consuming most CPU
jstall most-work 12345

# Monitor all threads in live mode
jstall threads --live 12345

# Monitor top 5 threads in live mode
jstall threads --top=5 --live 12345

# Detect threads stuck waiting on locks
jstall waiting-threads 12345

# Show thread dependency graph (which threads wait on which)
jstall dependency-tree 12345

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

### Global Options

| Option | Description |
|--------|----------|
| `-f, --file=<zip>` | Replay mode from a recording ZIP |
| `-s, --ssh=<prefix>` | Run commands on remote host via SSH (e.g., `ssh user@host`) |
| `--cf=<app>` | Cloud Foundry remote execution (shortcut for `--ssh 'cf ssh <app> -c'`) |
| `-v, --verbose` | Verbose logging of remote commands |

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

For full command reference with all options, see [docs/COMMANDS.md](docs/COMMANDS.md).

| Command | Description | Key Options |
|---------|-------------|-------------|
| `status` | Run multiple analyzers (default command) | `--top=<n>`, `--intelligent-filter`, `--full`, `--no-native` |
| `deadlock` | Detect JVM-reported thread deadlocks | |
| `most-work` | Identify threads doing the most work | `--top=<n>`, `--stack-depth=<n>`, `--intelligent-filter` |
| `threads` | List all threads sorted by CPU time | `--no-native` |
| `waiting-threads` | Identify threads waiting without progress | `--stack-depth=<n>`, `--intelligent-filter` |
| `dependency-graph` | Show thread lock dependencies | |
| `dependency-tree` | Show dependencies over time | |
| `flame` | Generate a flamegraph via async-profiler | `--duration=<t>`, `--event=<e>`, `--open` |
| `compiler-queue` | Analyze JIT compiler queue state | |
| `record` | Record diagnostic data into a zip | |
| `list` | List running JVM processes | `--no-truncate` |
| `processes` | Detect high-CPU non-JVM processes | |
| `jvm-support` | Check if JVM version is still supported | |
| `vm-vitals` | Show VM.vitals (SapMachine) | |
| `gc-heap-info` | Show GC heap info and change | |
| `vm-classloader-stats` | Show classloader stats | |
| `vm-metaspace` | Show metaspace summary and trend | |

**Common options** (available on most commands):
- `--dump-count=<n>` / `--interval=<t>` — Control dump collection
- `--intelligent-filter` — Collapse framework internals, focus on app code
- `-l, --live` — Live mode: interactive TUI with continuous monitoring
- `--color` — Enable colored output in live mode
- `-f, --file=<zip>` — Replay mode from a recording ZIP
- `--no-native` — Ignore threads without stack traces

**Exit codes:**
- `0` = no issues
- `1` = no JVMs found (for `list`)
- `2` = deadlock detected
- `10` = JVM is totally outdated (> 1 year)

---

### `status` (default)

Runs deadlock detection, most-work, threads, dependency-graph, and dependency-tree over shared thread dumps. Requires at least 2 dumps.

Also performs a **JVM support check**: collects `java.version.date` from `jcmd VM.system_properties` and warns if the JVM is > 4 months old (exit code 10 if > 1 year old).

```bash
jstall status 12345
jstall status MyApplication --top 5 --intelligent-filter
jstall status all --full
```

---

### `most-work`

Shows CPU time, CPU percentage, core utilization, state distribution, and activity categorization for top threads.

```bash
jstall most-work 12345 --top 5 --stack-depth 15 --intelligent-filter
```

---

### `waiting-threads`

Finds threads stuck waiting on the same lock instance across all dumps with no CPU progress (WAITING/TIMED_WAITING, CPU ≤ 0.0001s).

```bash
jstall waiting-threads 12345 --intelligent-filter
```

---

### `dependency-graph`

Shows which threads wait on locks held by others, detects dependency chains, and categorizes threads by activity.

**Example Output:**
```
Thread Dependency Graph
======================

[I/O Write] file-writer
  → [Network] netty-worker-1 (lock: <0xBBBB>)
     Waiter state: BLOCKED, CPU: 2.10s
     Owner state:  BLOCKED, CPU: 5.20s

Dependency Chains Detected:
---------------------------
Chain: [Database] jdbc-connection-pool → [I/O Write] file-writer → [Network] netty-worker-1
```

---

### `flame`

Generates a flamegraph using [async-profiler](https://github.com/async-profiler/async-profiler). Filter must match exactly one JVM.

```bash
jstall flame 12345 --duration 15s --output flame.html
jstall flame MyApp --event alloc --duration 20s --open
```

Events: `cpu`, `alloc`, `lock`, `wall`, `itimer`

---

### `compiler-queue`

Analyzes JIT compiler queue state over time using `jcmd Compiler.queue`.

```bash
jstall compiler-queue 12345
```

---

### Live Mode

Monitor a JVM continuously with an interactive TUI (Linux/macOS only):

```bash
jstall status 12345 --live
jstall status MyApp --live --color
jstall threads 12345 --live 
```

> **Note:** `--live` requires a Unix-like environment (Linux, macOS, or WSL). It is not available on Windows.

**Features:**
- Async data collection (UI stays responsive)
- Tab navigation between analyzers (Tab/Shift-Tab)
- Sort by column (`1-9`), secondary sort (`s` then `1-9`)
- Filter rows (`/`)
- Adjust collection interval (`+`/`-`), force refresh (`r`)
- Colored output with `--color` (green=RUNNABLE, red=BLOCKED, yellow=WAITING, CPU% intensity)
- Scroll (`j`/`k` or arrows), horizontal pan (`h`/`l`)
---

## Recording & Replay

Record diagnostic data for later analysis or sharing:

```bash
jstall record 12345 -o myapp-diagnostics.zip
jstall record all -o full-system.zip --full   # includes expensive jcmd commands
```

Replay on any machine:

```bash
jstall status myapp-diagnostics.zip
jstall -f myapp-diagnostics.zip threads all
jstall record summary myapp-diagnostics.zip
jstall record extract myapp-diagnostics.zip ./output-folder
```

---

## Thread Activity Categorization

JStall automatically categorizes threads by their activity based on stack trace analysis:

| Category | Description |
|----------|-------------|
| Network Read/Write | Socket operations, accept calls |
| Network | Selectors, polling, Netty |
| I/O Read/Write | File input/output operations |
| Database | JDBC and SQL operations |
| External Process | Waiting on external processes |
| Lock Wait | Waiting on locks/monitors |
| Sleep | Thread.sleep() calls |
| Park | LockSupport.park() calls |
| Computation | Active computation |
| Unknown | Unrecognized activity |

---

## Intelligent Stack Trace Filtering

Use `--intelligent-filter` to collapse framework internals and focus on application code.

```bash
jstall most-work 12345 --intelligent-filter --stack-depth 15
```

**Before:**
```
at com.example.MyController.handleRequest(MyController.java:42)
at jdk.internal.reflect.GeneratedMethodAccessor123.invoke(Unknown Source)
at jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(...)
at java.lang.reflect.Method.invoke(Method.java:566)
at org.springframework.web.method.support.InvocableHandlerMethod.invoke(...)
```

**After:**
```
at com.example.MyController.handleRequest(MyController.java:42)
... (3 internal frames omitted)
at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(...)
at com.example.Service.processRequest(Service.java:78)
```

---

## Use with Claude Code / GitHub Copilot (MCP)

jstall ships an [MCP server](mcp/) that lets Claude Code and GitHub Copilot inspect running JVMs directly.

**Claude Code:**
```bash
claude mcp add jstall -- npx -y @bechberger/jstall-mcp@latest
```

**GitHub Copilot (VS Code):**
```bash
code --add-mcp '{"name":"jstall","command":"npx","args":["-y","@bechberger/jstall-mcp@latest"]}'
```

Restart Claude Code or reload the VS Code window, then ask things like:

- *"What is my Java application doing right now?"*
- *"Is there a deadlock in PID 12345?"*
- *"Show me the hottest threads in my Spring Boot app."*

Claude will automatically call `jstall_list_jvms` to discover running JVMs, then use
`jstall_status` or other tools to diagnose the issue and explain the result.

### Claude Code skill

A skill is bundled with the MCP package and teaches Claude the investigation workflow
(deadlock analysis, high CPU, memory allocation, remote JVMs). Install it with:

```bash
ln -s "$(npm root -g)/@bechberger/jstall-mcp/skills/jstall" ~/.claude/skills/jstall
```

Or if you have jstall installed, use the hidden helper command:

```bash
jstall install-claude-skill
```

See the [MCP README](mcp/README.md) for full documentation including remote JVM usage,
flamegraph generation, and offline recording analysis.

---

## AI-Powered Analysis (Experimental)

> **Note:** AI analysis is experimental but visible in `jstall --help`. Output structure may still change between releases.

JStall includes an AI command that sends thread dump data to an LLM for natural-language analysis. It supports remote (Gardener) and local (any OpenAI-compatible server) providers.

```bash
jstall ai 12345                                    # analyze a JVM with the configured provider
jstall ai 12345 --provider local                   # force local provider
jstall ai 12345 -m gpt-4                           # override model
jstall ai 12345 -q "Is there a deadlock?"          # ask a specific question
echo "what's blocking thread main?" | jstall ai 12345   # piped stdin becomes the question
jstall ai chat 12345                               # initial analysis, then interactive REPL
jstall ai full                                     # analyze all JVMs on the system
```

### Configuration

Create a `.jstall-ai-config` file in the current directory or home directory:

**Remote (Gardener):**
```properties
provider=gardener
model=gpt-50-nano
api.key=your-api-key
```

**Local OpenAI-compatible server** (llama-server, vLLM, LocalAI, etc.):
```properties
provider=local
local.host=http://127.0.0.1:8080
```

**Auto-launch llama-server** — if `llama-server` is installed and no server is running, JStall can launch one automatically with a HuggingFace model:
```properties
provider=local
local.host=http://127.0.0.1:8080
local.llama-server-model=AaryanK/Qwen3.5-9B-GGUF:Q8_0
```

The server is started in the background with the specified model and stopped when JStall exits. Model files are cached in `$HF_HOME` (defaults to `~/.cache/huggingface`).

### AI Options

| Option | Description |
|--------|-------------|
| `-p, --provider <auto\|local\|remote>` | Pick the LLM provider (default: `auto`, from config) |
| `-m, --model <name>` | Override LLM model |
| `-q, --question <q>` | Custom question; piped stdin is used automatically when this is omitted |
| `--tools <on\|off>` | Tool calling (default: `on`; ignored for providers without tool support) |
| `--src <dir>` | Source root for file-exploration tools (default: auto-detected via `.git`) |
| `--reasoning` | Show the model's pre-tool reasoning and `<think>` blocks (local provider only) |
| `--short` | Produce a succinct summary |
| `--raw` | Output raw JSON response |
| `--dry-run` | Show prompt without calling the AI |
| `--no-pretty` | Disable markdown rendering (default: on when connected to a terminal) |
| `--quiet` | Suppress progress output (default: shown on a terminal) |
| `--save <file>` | Write the final analysis to a file |
| `--allow-mutations` | Let the AI invoke side-effecting commands (flame, record create) with confirmation |

### Tool Calling (Interactive Analysis)

When using a local OpenAI-compatible provider, tool calling is **enabled by default**. The LLM first receives the `jstall status` summary, then can use tools to dig deeper into specific threads or issues it wants to investigate further.

```bash
jstall ai 12345                          # tools on by default
jstall ai 12345 --tools off              # summarize only, no tool calls
```

Available tools the AI can use:
- **get_thread_stack_trace** — full stack trace of a thread (optionally from a specific dump)
- **search_stack_frames** — find threads with a class/method in their stack
- **get_lock_info** — lock holders and blocked threads
- **get_top_cpu_threads** — top N threads by CPU consumption
- **get_dependency_tree** — thread wait chains (who blocks whom)
- **compare_thread_across_dumps** — track a thread across multiple dumps to see if it's stuck
- **get_system_properties** — JVM configuration and properties
- **get_raw_thread_dump_section** — raw dump text for a specific thread

The AI makes up to 5 tool-call rounds before producing its final answer. Progress is shown on stderr (e.g., `[tool 1/5] get_thread_stack_trace(thread_name=main)`).

### Interactive Chat

`jstall ai chat <pid>` collects dumps, runs status analysis, and seeds the conversation with a short 3-5 bullet summary so the REPL prompt appears quickly. The full status data stays in conversation history for follow-up questions, and tool calling is enabled from turn 2 onward — including `run_jstall_command` for fresh dumps and (with per-call y/N confirmation) `flame` and `record create`. Pass `--no-mutations` to disable side-effecting commands. Builtins inside the REPL: `/help`, `/clear`, `/save <file>`, `/exit`. Multi-line input is supported with a trailing `\`. Local provider only.

---

## Usage as a Library

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jstall</artifactId>
    <version>0.7.1</version>
</dependency>
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, test configuration, and how to extend JStall.

## Support & Feedback

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub issues](https://github.com/parttimenerd/jstall/issues).
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2025 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors