# jstall MCP Server

[![npm](https://img.shields.io/npm/v/@bechberger/jstall-mcp)](https://www.npmjs.com/package/@bechberger/jstall-mcp)

MCP server for [jstall](https://github.com/parttimenerd/jstall) — exposes JVM diagnostics as tools for Claude and other MCP clients.

**Ask Claude things like:**
- "What is my Java application doing right now?"
- "My Spring Boot app seems slow — is there a hot thread?"
- "Is there a deadlock in PID 12345?"
- "Generate a flamegraph for my app"

## Requirements

- Node.js 18+
- Java 17+ on the machine running the server (target JVM can be Java 11+)

## Installation

### Claude Desktop / Claude Code

Add to your MCP config (`~/.claude/settings.json` or Claude Desktop settings):

```json
{
  "mcpServers": {
    "jstall": {
      "command": "npx",
      "args": ["-y", "@bechberger/jstall-mcp"]
    }
  }
}
```

The JAR is bundled in the npm package — no separate jstall installation needed.

### With a local jstall build

```json
{
  "mcpServers": {
    "jstall": {
      "command": "npx",
      "args": ["-y", "@bechberger/jstall-mcp"],
      "env": {
        "JSTALL_JAR": "/path/to/jstall/target/jstall.jar"
      }
    }
  }
}
```

## Tools

| Tool | Description |
|---|---|
| `jstall_run` | Run any jstall command on a local JVM |
| `jstall_remote` | Run any jstall command on a remote JVM via SSH or Cloud Foundry |
| `jstall_help` | Show help for any jstall command |

### `jstall_run`

```json
{ "args": ["status", "--intelligent-filter", "--no-native", "12345"] }
{ "args": ["list"] }
{ "args": ["flame", "--output=/tmp/flame.html", "--duration=15s", "12345"] }
{ "args": ["record", "create", "--count=3", "--output=/tmp/rec.zip", "12345"] }
```

### `jstall_remote`

```json
{ "type": "ssh", "target": "user@prod-host", "args": ["list"] }
{ "type": "ssh", "target": "user@prod-host", "args": ["status", "--intelligent-filter", "--no-native", "12345"] }
{ "type": "cf",  "target": "my-cf-app",      "args": ["status", "--intelligent-filter", "12345"] }
```

Remote support requires jstall available on the remote host (in PATH or via [jbang](https://www.jbang.dev/)). Linux/macOS only on the remote side.

### `jstall_help`

```json
{}                            // list all commands
{ "command": "status" }       // flags for status
{ "command": "flame" }        // flags for flame
{ "command": "record create"} // flags for record create subcommand
```

## Environment Variables

| Variable | Description |
|---|---|
| `JSTALL_JAR` | Path to `jstall.jar` — overrides bundled JAR |
| `JSTALL_JAVA` | Path to `java` binary — overrides auto-detected Java 17+ |

## JAR Resolution

The server resolves `jstall.jar` in this order:

1. `--jar <path>` CLI argument
2. `JSTALL_JAR` environment variable
3. Bundled `lib/jstall.jar` in the npm package
4. `../../target/jstall.jar` relative to the package (local repo build)

## Claude Code Skill

A Claude Code skill is bundled at `skills/jstall/SKILL.md`. It teaches Claude the standard diagnostic workflow, triage decision tree, and scenario runbooks. Install it globally:

```bash
ln -s "$(npm root -g)/@bechberger/jstall-mcp/skills/jstall" ~/.claude/skills/jstall
```

Or if using a local clone:

```bash
ln -s /path/to/jstall/mcp/skills/jstall ~/.claude/skills/jstall
```

## Development

```bash
git clone https://github.com/parttimenerd/jstall
cd jstall/mcp
npm install          # downloads jstall.jar and compiles TypeScript
npm run dev          # download JAR + compile + run server
```

To use a local jstall build instead of downloading:

```bash
cd jstall && mvn package -DskipTests
cd mcp && npm install   # picks up ../target/jstall.jar automatically
```

## License

MIT
