# jstall MCP Server

[![npm](https://img.shields.io/npm/v/@bechberger/jstall-mcp)](https://www.npmjs.com/package/@bechberger/jstall-mcp)

MCP server for [jstall](https://github.com/parttimenerd/jstall) — exposes JVM diagnostics as tools for Claude and other MCP clients.

## Requirements

- Node.js 18+
- Java 17+ on the machine running the server (target JVM can be Java 11+)

## Quick install (Claude Code)

The simplest way if you don't have jstall installed yet:

```bash
claude mcp add jstall -- npx -y @bechberger/jstall-mcp@latest
```

If you already have jstall installed, you can use the built-in command instead:

```bash
jstall install-claude-mcp    # registers the MCP server in ~/.claude/settings.json
jstall install-claude-skill  # installs the Claude Code skill to ~/.claude/skills/jstall/
```

Then restart Claude Code and you're ready to go.

## Quick install (GitHub Copilot in VS Code)

```bash
code --add-mcp '{"name":"jstall","command":"npx","args":["-y","@bechberger/jstall-mcp@latest"]}'
```

Then reload the VS Code window. The jstall tools will be available in Copilot Chat.

## Manual installation

Add to `~/.claude/settings.json` (Claude Code) or your Claude Desktop MCP config:

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

To use a local jstall build instead of downloading the JAR:

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

## Usage with Claude

Once installed, talk to Claude naturally. Claude discovers JVMs, picks the right commands, and interprets the output.

**Typical starting points:**

```
"What is my Java application doing right now?"
"My Spring Boot app is slow — what's happening?"
"Is there a deadlock in PID 12345?"
"Generate a flamegraph for my app."
"Diagnose the JVM running MyService on prod-host."
```

**What Claude does:**

1. Calls `jstall_run(["list"])` to discover running JVMs (unless you gave a PID or name)
2. Calls `jstall_run(["status", "--intelligent-filter", "--no-native", "<pid>"])` for an initial read
3. Interprets the output and follows up with targeted commands:
   - High CPU → `most-work` + `flame`
   - Deadlock → `deadlock` + `dependency-graph`
   - Stuck threads → multi-dump `status` + `waiting-threads` + `dependency-tree`
   - Memory growth → `gc-heap-info` + allocation flamegraph
   - Classloader leak → `vm-metaspace` + `vm-classloader-stats`

**Remote JVMs:**

```
"List JVMs on user@prod-host"
"Run a status check on the Cloud Foundry app my-cf-app"
"Is there a deadlock in the app running on staging-server?"
```

Claude uses `jstall_remote` automatically when you mention a hostname or CF app name.

**Flamegraphs:**

Claude will tell you the output path and ask you to open it in a browser. The profiling step blocks for the duration (default 10s), so expect a short wait.

```
"Generate a CPU flamegraph for PID 12345"
→ Claude runs flame, reports: "Flamegraph saved to /tmp/flame-12345.html — open in your browser."

"Profile memory allocations for 20 seconds"
→ Claude runs flame --event=alloc --duration=20s
```

**Recordings:**

```
"Capture a recording of PID 12345 for later analysis"
→ Claude runs record create, tells you the ZIP path

"Analyze this recording: /tmp/rec.zip"
→ Claude runs status + flame against the ZIP
```

## Claude Code Skill

Install the skill to give Claude a built-in triage decision tree and scenario runbooks:

```bash
jstall install-claude-skill
```

Or manually, from the npm package:

```bash
ln -s "$(npm root -g)/@bechberger/jstall-mcp/skills/jstall" ~/.claude/skills/jstall
```

Or from a local clone:

```bash
ln -s /path/to/jstall/mcp/skills/jstall ~/.claude/skills/jstall
```

The skill activates automatically when you ask about JVM performance, deadlocks, profiling, or anything jstall-related.

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
{}                             // list all commands
{ "command": "status" }        // flags for status
{ "command": "flame" }         // flags for flame
{ "command": "record create" } // flags for record create subcommand
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

## Development

```bash
git clone https://github.com/parttimenerd/jstall
cd jstall/mcp
npm install          # downloads jstall.jar and compiles TypeScript
npm run build        # compile TypeScript only
npm test             # run test suite (77 tests; integration tests skip if JAR absent)
```

To use a local jstall build instead of downloading:

```bash
cd jstall && mvn package -DskipTests
cd mcp && npm install   # picks up ../target/jstall.jar automatically
```

## Publishing to npm

1. Bump the version in `package.json` and `src/server.ts` to match the jstall release.
2. Build and test:
   ```bash
   npm run build
   npm test
   ```
3. Publish (requires npm OTP if 2FA is enabled):
   ```bash
   npm publish --access public --otp=<your-OTP>
   ```

## License

MIT
