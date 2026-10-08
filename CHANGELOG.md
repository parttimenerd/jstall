# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.8.2] - 2026-10-08

### Added
- `vm-vitals`: robust rewrite of the parser covering all SapMachine 11–27 text and CSV variants
- `vm-vitals`: **Trends** table after the raw data — one row per non-delta column showing first value, last value, and a direction arrow (`→` stable, `↑`/`↓` monotone with net delta, `~` oscillating with value range)
- `vm-vitals`: **Observations** section with automatic signals: heap pressure >80%, heap growing >5%, metaspace growing, metaspace near GC threshold, thread count grew ≥5, class count grew ≥50, CPU steal >10%, swap growing, RSS growing
- `vm-vitals`: fixed duplicate-column-name data loss (heap-comm and meta-comm both named `comm` in the table; now tracked positionally)

### Changed
- `vm-vitals`: legend entries no longer show noise tags like `[cs]` or `[linux]` in descriptions; `[delta]` note still shown

## [0.8.1] - 2026-10-07

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.8.0] - 2026-10-07

### Added
- JRE-only container support: `status` and all jcmd-based diagnostics now work on containers
  without `jcmd`/`jps` by falling back to the HotSpot attach socket via `nc -U`.
  Requires `netcat-openbsd` or `nmap-ncat` on the container. Supports JDK 9–25 on Linux and macOS.
  The attach socket is created on demand if absent; a clear error is shown if `nc` is missing.

### Fixed
- Remote JDK discovery no longer runs a slow `find /` scan when `jps` is already on PATH;
  `command -v jps` is tried first (~1 ms vs 3+ seconds on some hosts).
- `getThreadDump()` called `executeCommand` with arguments reversed; thread dumps via JMX now work correctly.
- `jcmd: not found` errors were silently swallowed in persistent-shell mode (stderr not captured);
  now wrapped in `sh -c "... 2>&1"` so the fallback to attach-socket mode triggers correctly.
- Persistent-shell batch execution could deadlock when `Thread.print` output exceeded the 64 KB
  pipe buffer; nc-pipeline commands are now pipelined while jcmd commands run sequentially.

## [0.7.3] - 2026-09-25

### Added
### Changed
### Deprecated
### Removed
### Fixed
- `--cf APP` and `--ssh` modes now work correctly on Windows: `.cmd`/`.bat` wrappers on PATH are resolved and invoked via `cmd.exe /c`, so fake-tool tests and real CF CLI scripts are both found when `allowAmbiguousCommands=false` is active
- Flamegraph HTML now auto-opens in the default browser on Windows (`cmd /c start`)
- `llama-server` detection uses `where` instead of `which` on Windows
- Replaced hardcoded `/dev/null` redirect with `ProcessBuilder.Redirect.DISCARD` (portable)
- All `split("\\n")` calls in analyzers and LLM utilities replaced with `split("\\R")` / `split("\\r?\\n")` to correctly handle CRLF output on Windows
- Removed hardcoded `/tmp`/`/var/tmp` Unix-only fallback paths from `SourceTools`
### Security

## [0.7.2] - 2026-09-22

### Fixed
- `--ssh` mode now works when running jstall on Windows: replaced the `sh -c "ssh ..."` shell wrapper with a direct `ProcessBuilder` invocation, so no local Unix shell is required. The remote host still needs to be Linux/Mac.

## [0.7.1] - 2026-05-17

### Added
- Local AI via generic OpenAI-compatible provider (`OpenAiLlmProvider`) with auto-launch of llama-server
- Tool-calling system for AI: 8 tools (`get_thread_stack_trace`, `search_stack_frames`, `get_lock_info`, `get_top_cpu_threads`, `compare_thread_across_dumps`, `get_dependency_tree`, `get_system_properties`, `get_raw_thread_dump_section`)
- `--no-tools` flag, `--think` flag for showing LLM reasoning, `--short` for succinct summaries
- Retry with exponential backoff on transient HTTP errors (429, 502, 503)
- Robust `<think>` tag handling for streaming (handles tags split across chunks)
- `TablePrinter` utility for formatted table output

### Changed
- Replaced Ollama provider with generic OpenAI-compatible provider; config keys are now `local.host` and `local.llama-server-model`
- `--short` mode no longer double-streams (full analysis is suppressed, only summary shown)

### Removed
- `OllamaLlmProvider` and Ollama-specific config keys (`ollama.host`, `ollama.think-mode`)

## [0.7.0] - 2026-05-07

### Added
- Live mode (`--live`): interactive TUI with async data collection, tab navigation, sorting, filtering, and scroll
- `--color` flag for colored output in live mode (thread states, CPU % intensity)
- Secondary sort in live mode: press `s` then `1-9` to add tiebreaker columns
- Interval adjustment (`+`/`-`) and force refresh (`r`) in live mode
- Collection/analysis timing display in live mode status bar
- Scroll position indicator in footer bar
- `--top=<n>` option for `threads` command to show only top N threads by CPU time (works with `--live` mode)

### Changed
- CPU time formatting: values below 10ms now display as milliseconds (e.g., `3ms`) instead of `0.00s`

### Fixed
- Comma-grouped numbers (e.g., `15,177`) now sort correctly in tables

## [0.6.2] - 2026-05-05

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.6.1] - 2026-04-14

### Changed
- Hide `ai` commands from default CLI help/listings for now; they remain available as experimental commands via direct invocation

## [0.6.0] - 2026-04-09

### Added
- Add `-s/--ssh` to use a specific shell command to execute jcmds and more
- Add `--cf` to support cloud foundry environments directly
- `dependency-tree` with proper detection of dependencies between threads

## [0.5.4] - 2026-03-19

### Changed
- Only `--full` mode does obtain flamegraph and JFR recording

## [0.5.3] - 2026-03-12

### Changed
- Update ap-loader version

## [0.5.2] - 2026-03-12

### Added
- Make SystemEnvironment parsing more robust

## [0.5.1] - 2026-03-12

### Added
- `all` target for running commands on all discovered JVMs or all recorded JVMs in replay mode
- Support for passing a recording file as a positional argument for commands that support replay mode

## [0.5.0] - 2026-03-12

### Added
- Add `jvm-support` command to check if a JVM is outdated
- Add `compiler-queue`, `gc-heap-info`, `vm-vitals`, ``vm-metaspace`, `processes` commands
- Add `record` and replay

### Fixed
- Fixed invalid call to AsyncProf in `flame` command
- Fixed CLI bug in `ai` commands

## [0.4.11] - 2026-01-28

### Added
- Support for running with Java 17

## [0.4.10] - 2026-01-28

### Added
- Basic support for SAP JVM thread dumps

## [0.4.9] - 2026-01-27

## [0.4.8] - 2026-01-27

### Fixed
- Threaddump timestamp parsing

## [0.4.7] - 2026-01-27


## [0.4.6] - 2026-01-27

### Fixed
- Fixed duration parsing
- Fixed minimal build issues
- Fixed flame command issues
- Fixed threaddump command issues

## [0.4.5] - 2026-01-27

### Added
- Created a minimal build aploader-minimal
  - Doesn't include async-profiler binaries, which isn't a problem with SapMachine and when using the `flame` command

### Changed
- Reduced the size of the JAR by removing picocli
  - Replaced it with a purpose built CLI library ([femtocli](https://github.com/parttimenerd/femtocli) with 30K vs >400K)

## [0.4.2] - 2026-01-12

### Added
- `waiting-threads` command to identify threads waiting on the same lock instance across all thread dumps with no CPU time progress
- Better name discovery for JVMs without a JMX label (using the process command as a fallback)

### Changed
- List output to make it more concise
- Improved README

### Removed
- GraalVM native image support due to complexity and maintenance overhead

## [0.4.1] - 2026-01-04

### Added
- GraalVM native image support

### Changed
- Improved performance by using the JMX API instead of calling external commands

## [0.4.0] - 2026-01-03

### Added
- `list` command
- Allow specifying JVM via a label filter

## [0.3.4] - 2025-12-30

- The releaser script doesn't like force pushes

## [0.3.3] - 2025-12-30

### Fixed
- Fixed `flame` command
- Removed `at at` from stack traces, making them easier to read

## [0.3.2] - 2025-12-30

### Fixed
- Fixed main help message

## [0.3.1] - 2025-12-30

### Changed
- Improve main help message

## [0.3.0] - 2025-12-30

### Fixed
- Fixed the `dead-lock` command and renamed it to `deadlock`

## [0.2.0] - 2025-12-29

### Changed
- use jstack for thread dump capture instead of jcmd
- remove `--json` option, as this lead to a lot of duplicated code
- add `threads` command

## [0.1.0] - 2025-12-29

### Added
- Initial implementation