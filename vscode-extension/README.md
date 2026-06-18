# JStall VSCode Extension

[![Build VS Code Extension](https://github.com/parttimenerd/jstall/actions/workflows/ide-vscode-build.yml/badge.svg)](https://github.com/parttimenerd/jstall/actions/workflows/ide-vscode-build.yml) [![Visual Studio Marketplace Version](https://img.shields.io/visual-studio-marketplace/v/bechberger.jstall)](https://marketplace.visualstudio.com/items?itemName=bechberger.jstall)

A tiny vscode extension to integrate the [jstall](https://github.com/parttimenerd/jstall) CLI tool,
giving you JVM diagnostics (thread analysis, deadlock detection, flame graphs, …) directly in your IDE,
instead of just basic thread dumps.

_You can find the IntelliJ plugin for JStall that offers similar features [here](https://github.com/parttimenerd/jstall/tree/main/intellij-plugin)._

JStall is aimed at the moment when a JVM looks suspicious, stuck, or just slower than expected,
and you want better diagnostics without switching to external tools.

The extension supports:

- Running JStall commands (**status**, **record**, **flame**) on a running JVM directly from the IDE,
  with automatic PID detection from debug sessions, run sessions, and terminal-launched Java processes
- Displaying flamegraphs in an interactive embedded viewer
- Analyzing and extracting JStall recording ZIP files via context menu or command palette
- Integration with GitHub Copilot and other AI assistants through custom Language Model Tools
- Remote JVM diagnostics via SSH or Cloud Foundry: run any JStall command against remote machines

_Flamegraphs are obtained via the embedded async-profiler and therefore is only supported
on macOS and Linux._

## Quick Start

1. Install the extension
2. Open a Java project
3. Run or debug a Java application
4. Run the JStall actions from the status bar or command palette to analyze the active JVM

Or (with GitHub Copilot Chat):
> "Analyze the status of all currently running JVMs"

And you get exactly that.

## Status Bar & Editor Toolbar

When a JVM is detected (from a debug session, run session, or terminal), the status bar shows:

> `$(play) JStall: MyApp (12345)` &nbsp; `Status` &nbsp; `Flame` &nbsp; `Record`

Click the JVM name for quick actions, or use the individual buttons.
The same **Status**, **Flame**, and **Record** buttons also appear in the editor title bar
when a JVM is active.

## Commands

### Live JVM Diagnostics

| Command | Description |
|---------|-------------|
| **JStall: Status** | Analyze a running JVM — thread dumps, deadlock detection, top threads |
| **JStall: Flamegraph** | Capture a CPU flamegraph and display it in an interactive HTML viewer |
| **JStall: Record** | Record JVM diagnostics over time into a ZIP file (auto-saved to `.jstall/` directory) |
| **JStall: Quick Actions** | Quick-pick menu for all three actions above |
| **JStall: Remote** | Connect to a remote JVM via SSH or Cloud Foundry and run any JStall command |

All commands auto-detect the active JVM from debug/run sessions.
When invoked from the command palette, they show a JVM picker with the active JVM pre-selected.

### Remote JVM Diagnostics

Use **JStall: Remote** (or pick "Remote" from Quick Actions) to diagnose JVMs on remote machines:

1. Choose connection type — **SSH** or **Cloud Foundry (CF)**
2. Pick a saved host/app or enter a new target (e.g. `user@hostname` or `my-cf-app`)
3. Select a command — List JVMs, Status, Flamegraph, Record, Threads, Deadlock, and more

The extension remembers your last-used connection. Configure known hosts/apps in settings
so they appear in the quick pick:

```json
{
  "jstall.remote.sshHosts": ["deploy@prod.example.com", "user@staging"],
  "jstall.remote.cfApps": ["my-backend", "api-service"]
}
```

### Recording File Actions

Right-click a `.zip` file in the Explorer to see a **JStall** submenu:

| Command | Description |
|---------|-------------|
| **JStall: Recording Summary** | Quick overview of the recording contents (JVMs, size, metadata) |
| **JStall: Replay Recording** | Run `jstall status` on a recording and display the full analysis |
| **JStall: Flamegraph from Recording** | Extract and display the embedded flamegraph |
| **JStall: Extract Recording** | Extract the recording ZIP contents into a folder |

These commands are also available from the command palette.
If a file is not a valid JStall recording, the commands will report a clear error.

## Flamegraph Viewer

The flamegraph viewer opens each flamegraph in its own VS Code tab, allowing side-by-side comparison.
Flamegraphs are interactive HTML visualizations powered by async-profiler.

## JVM Detection

The extension automatically detects running JVMs from three sources:

1. **Debug sessions** — Java debug sessions started from VS Code
2. **Run sessions** — "Run without Debugging" sessions (noDebug mode)
3. **Terminal processes** — Java processes launched from the integrated terminal (polled every 3 seconds)

The active JVM is shown in the status bar and pre-selected in the JVM picker.

## AI Assistant Integration

The extension registers five **Language Model Tools** for use by GitHub Copilot and other AI assistants:

| Tool | Description |
|------|-------------|
| `#jstall_list_jvms` | List all running JVM processes |
| `#jstall_run` | Run any jstall CLI command (deadlock, threads, vm-vitals, etc.) |
| `#jstall_status` | Full JVM status diagnostics with all parameters (live or recording) |
| `#jstall_flamegraph` | Capture a CPU profiling flamegraph (live or from recording) |
| `#jstall_record` | Record JVM diagnostics to a ZIP file |
| `#jstall_remote` | Run any JStall command on a remote JVM via SSH or Cloud Foundry |

Use these in Copilot Chat by referencing the tool name, e.g.:
> "Use #jstall_run to check what my Java app is doing"

## Settings

Configurable under **Settings → Extensions → JStall**:

| Setting | Description | Default |
|---------|-------------|---------|
| `jstall.javaPath` | Path to Java 17+ executable (empty = auto-detect) | `""` |
| `jstall.fullDiagnostics` | Include expensive analyses (VM vitals, metaspace, etc.) | Off |
| `jstall.intelligentFilter` | Collapse framework internals in stack traces | Off |
| `jstall.noNative` | Skip threads without Java stack traces | Off |
| `jstall.keep` | Persist thread dumps to disk | Off |
| `jstall.top` | Number of top threads to display | 3 |
| `jstall.recordIntervalSeconds` | Seconds between recording samples | 5 |
| `jstall.flameDurationSeconds` | Profiling duration in seconds | 10 |
| `jstall.recordingDir` | Directory for recordings (relative to workspace root) | `.jstall` |
| `jstall.remote.sshHosts` | Known SSH connections for Remote | `[]` |
| `jstall.remote.cfApps` | Known Cloud Foundry app names for Remote | `[]` |

And the same for the MCP tool defaults.

MCP-specific defaults are configurable with:

- `jstall.mcp.status.*`
- `jstall.mcp.flamegraph.*`
- `jstall.mcp.record.*`

## Keyboard Shortcuts

| Shortcut | Command |
|----------|---------|
| <kbd>Cmd+Shift+J</kbd> / <kbd>Ctrl+Shift+J</kbd> | JStall: Quick Actions |

## Requirements

- **Java 17+** — The extension auto-detects Java from `JAVA_HOME`, macOS `java_home`, common paths, and `PATH`.
  Override with the `jstall.javaPath` setting if needed.
- **jstall.jar** — Automatically downloaded from GitHub Releases at build time. Run `npm run download-jar` if missing.

## Installation

Just install the extension from the [marketplace](https://img.shields.io/visual-studio-marketplace/v/bechberger.jstall), from the release page, or build and run from source.

### From Source

```bash
cd jstall/vscode-extension
npm install
npm run compile
npm install
npx @vscode/vsce package
```

Install the resulting `.vsix` file via **Extensions → ⋯ → Install from VSIX…**

## Releasing a new version

1. Update the version in `package.json` (e.g. to `0.1.1`)
2. Update the `CHANGELOG.md` with the new version and changes
3. Commit the changes and push to GitHub, tagging the commit with the version (e.g. `git tag v0.1.1`)
4. Upload to the marketplace

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors
