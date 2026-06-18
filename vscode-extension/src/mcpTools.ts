/**
 * Language Model Tools — exposes JStall functionality to AI assistants
 * (GitHub Copilot, etc.) via VS Code's Language Model Tools API.
 *
 * Uses a lightweight registerTool helper (inspired by the Slidev VS Code
 * extension) that removes per-tool class boilerplate: each tool is just an
 * async function `(input, token) => string`.
 *
 * Tools:
 *  - jstall_list_jvms  — convenience: list running JVMs with active marker
 *  - jstall_run        — generic: run any jstall command with arbitrary args
 *  - jstall_status     — typed: JVM status diagnostics with all parameters
 *  - jstall_flamegraph — special: captures flamegraph HTML and returns path
 *  - jstall_record     — special: records diagnostics ZIP and returns path
 */

import * as vscode from 'vscode';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';
import { runJstall, stripAnsi, resolveRecordingSavePath, runJavaProfiled } from './jstall';
import { listJvms } from './jvmPicker';
import { getActiveJvm } from './debugIntegration';
import { resolveJavaClassToFile, revealCodeLocation, clearAllHighlights } from './codeNavigation';

// ─── helpers ──────────────────────────────────────────────────────

type PidSelector = number | 'all';

function resolveTarget(input: { pid?: number | string }): PidSelector | undefined {
    if (input.pid === 'all') {
        return 'all';
    }
    if (typeof input.pid === 'number') {
        return input.pid;
    }
    if (typeof input.pid === 'string') {
        const maybePid = Number.parseInt(input.pid, 10);
        if (Number.isInteger(maybePid) && maybePid > 0) {
            return maybePid;
        }
    }
    return getActiveJvm()?.pid;
}

function requireNumericPid(input: { pid?: number | string }): number {
    const target = resolveTarget(input);
    if (target === 'all') {
        throw new Error('"all" is not supported for this command. Please provide a specific PID.');
    }
    if (!target) {
        throw new Error(
            'No PID specified and no active JVM detected. ' +
            'Use jstall_list_jvms to find running JVMs first.'
        );
    }
    return target;
}

function requireStatusOrRecordTarget(input: { pid?: number | string }): PidSelector {
    const target = resolveTarget(input);
    if (target !== undefined) {
        return target;
    }
    throw new Error(
        'No PID specified and no active JVM detected. ' +
        'Use jstall_list_jvms to find running JVMs first, or set pid to "all".'
    );
}

function jstallOutput(result: { stdout: string; stderr: string; exitCode: number }): string {
    const output = stripAnsi(
        result.stdout + (result.stderr ? '\n' + result.stderr : '')
    ).trim();
    return output || `Command exited with code ${result.exitCode} and produced no output.`;
}

interface McpStatusDefaults {
    full: boolean;
    intelligentFilter: boolean;
    noNative: boolean;
    keep: boolean;
    top: number;
    dumps?: number;
    interval?: string;
}

interface McpFlamegraphDefaults {
    durationSeconds: number;
    event: string;
    interval: string;
}

interface McpRecordDefaults {
    full: boolean;
    count: number;
    interval: string;
}

function getMcpStatusDefaults(): McpStatusDefaults {
    const config = vscode.workspace.getConfiguration('jstall');
    return {
        full: config.get<boolean>('mcp.status.full', config.get<boolean>('fullDiagnostics', false)),
        intelligentFilter: config.get<boolean>('mcp.status.intelligentFilter', config.get<boolean>('intelligentFilter', true)),
        noNative: config.get<boolean>('mcp.status.noNative', config.get<boolean>('noNative', true)),
        keep: config.get<boolean>('mcp.status.keep', config.get<boolean>('keep', false)),
        top: config.get<number>('mcp.status.top', config.get<number>('top', 3)),
        dumps: config.get<number | null>('mcp.status.dumps', null) ?? undefined,
        interval: config.get<string>('mcp.status.interval', '').trim() || undefined,
    };
}

function getMcpFlamegraphDefaults(): McpFlamegraphDefaults {
    const config = vscode.workspace.getConfiguration('jstall');
    return {
        durationSeconds: config.get<number>('mcp.flamegraph.durationSeconds', config.get<number>('flameDurationSeconds', 10)),
        event: config.get<string>('mcp.flamegraph.event', 'cpu').trim() || 'cpu',
        interval: config.get<string>('mcp.flamegraph.interval', '10ms').trim() || '10ms',
    };
}

function getMcpRecordDefaults(): McpRecordDefaults {
    const config = vscode.workspace.getConfiguration('jstall');
    const intervalSeconds = config.get<number>('recordIntervalSeconds', 5);
    return {
        full: config.get<boolean>('mcp.record.full', config.get<boolean>('fullDiagnostics', false)),
        count: config.get<number>('mcp.record.count', 2),
        interval: config.get<string>('mcp.record.interval', `${intervalSeconds}s`).trim() || `${intervalSeconds}s`,
    };
}

/**
 * Registers a language-model tool with centralised error handling.
 * Each tool is just an async function that returns a plain string —
 * the helper wraps it in a LanguageModelToolResult and catches errors.
 */
function registerTool<T>(
    context: vscode.ExtensionContext,
    name: string,
    invoke: (input: T, token: vscode.CancellationToken) => string | Promise<string>,
): void {
    context.subscriptions.push(
        vscode.lm.registerTool<T>(name, {
            async invoke(options, token) {
                try {
                    const text = await invoke(options.input, token);
                    return new vscode.LanguageModelToolResult([
                        new vscode.LanguageModelTextPart(text),
                    ]);
                } catch (error: unknown) {
                    const msg = error instanceof Error ? error.message : String(error);
                    return new vscode.LanguageModelToolResult([
                        new vscode.LanguageModelTextPart(`Error: ${msg}`),
                    ]);
                }
            },
        }),
    );
}

// ─── Registration ─────────────────────────────────────────────────

export function registerMcpTools(context: vscode.ExtensionContext): void {
    if (!vscode.lm || typeof vscode.lm.registerTool !== 'function') {
        console.log('JStall: Language Model Tools API not available, skipping tool registration.');
        return;
    }

    // ── List JVMs ─────────────────────────────────────────────────
    registerTool<Record<string, never>>(context, 'jstall_list_jvms', async () => {
        const jvms = await listJvms(context);
        if (jvms.length === 0) {
            return 'No JVM processes found.';
        }
        const activeJvm = getActiveJvm();
        return jvms
            .map(jvm => {
                const marker = activeJvm?.pid === jvm.pid ? ' (active)' : '';
                return `PID ${jvm.pid}: ${jvm.name}${marker}`;
            })
            .join('\n');
    });

    // ── Run (generic) ─────────────────────────────────────────────
    registerTool<{ args: string[] }>(context, 'jstall_run', async (input, token) => {
        const args = input.args ?? [];
        if (args.length === 0) {
            throw new Error('No arguments provided. Try ["--help"] to see available commands.');
        }
        return jstallOutput(await runJstall(context, args, token));
    });

    // ── Status ────────────────────────────────────────────────────
    registerTool<{
        pid?: number | 'all';
        recordingZip?: string;
        full?: boolean;
        intelligentFilter?: boolean;
        noNative?: boolean;
        keep?: boolean;
        top?: number;
        dumps?: number;
        interval?: string;
    }>(context, 'jstall_status', async (input, token) => {
        const defaults = getMcpStatusDefaults();
        const statusArgs: string[] = [];

        // Toggle boolean flags
        const boolFlags: [string, boolean | undefined][] = [
            ['--full', input.full ?? defaults.full],
            ['--intelligent-filter', input.intelligentFilter ?? defaults.intelligentFilter],
            ['--no-native', input.noNative ?? defaults.noNative],
            ['--keep', input.keep ?? defaults.keep],
        ];
        for (const [flag, val] of boolFlags) {
            if (val) { statusArgs.push(flag); }
        }

        const top = input.top ?? defaults.top;
        if (top !== 3) {
            statusArgs.push(`--top=${top}`);
        }
        const dumps = input.dumps ?? defaults.dumps;
        if (dumps !== undefined) {
            statusArgs.push(`--dumps=${dumps}`);
        }
        const interval = input.interval ?? defaults.interval;
        if (interval) {
            statusArgs.push(`--interval=${interval}`);
        }

        const args: string[] = ['status', ...statusArgs];

        if (input.recordingZip) {
            args.push(input.recordingZip);
        } else {
            args.push(String(requireStatusOrRecordTarget(input)));
        }

        return jstallOutput(await runJstall(context, args, token));
    });

    // ── Flamegraph ────────────────────────────────────────────────
    registerTool<{
        pid?: number;
        durationSeconds?: number;
        event?: string;
        interval?: string;
        recordingZip?: string;
    }>(context, 'jstall_flamegraph', async (input, token) => {
        const tmpFile = path.join(os.tmpdir(), `jstall-flame-${Date.now()}.html`);
        const defaults = getMcpFlamegraphDefaults();

        if (input.recordingZip) {
            const args = ['-f', input.recordingZip, 'flame'];
            if (input.pid) { args.push(String(input.pid)); }
            args.push('-o', tmpFile);
            const result = await runJstall(context, args, token);

            if (result.exitCode === 0 && fs.existsSync(tmpFile)) {
                return `Flamegraph from recording saved to ${tmpFile}. Open it in a browser.`;
            }
            throw new Error(`Flamegraph from recording failed: ${stripAnsi(result.stderr || result.stdout)}`);
        }

        const pid = requireNumericPid(input);
        const flameArgs: string[] = [];

        const durationSeconds = input.durationSeconds ?? defaults.durationSeconds;
        if (durationSeconds !== 10) {
            flameArgs.push(`--duration=${durationSeconds}s`);
        }
        const event = input.event ?? defaults.event;
        if (event !== 'cpu') {
            flameArgs.push(`--event=${event}`);
        }
        const interval = input.interval ?? defaults.interval;
        if (interval !== '10ms') {
            flameArgs.push(`--interval=${interval}`);
        }

        const args = ['flame', ...flameArgs, '-o', tmpFile, String(pid)];
        const result = await runJstall(context, args, token);

        if (result.exitCode === 0 && fs.existsSync(tmpFile)) {
            return `Flamegraph captured for PID ${pid} and saved to ${tmpFile}. ` +
                'Open it in a browser for an interactive CPU profiling flamegraph.';
        }

        throw new Error(`Flamegraph capture failed: ${stripAnsi(result.stderr || result.stdout)}`);
    });

    // ── Record ────────────────────────────────────────────────────
    registerTool<{
        pid?: number | 'all';
        full?: boolean;
        count?: number;
        interval?: string;
    }>(context, 'jstall_record', async (input, token) => {
        const target = requireStatusOrRecordTarget(input);
        const { savePath } = resolveRecordingSavePath(target);
        const defaults = getMcpRecordDefaults();

        const recordArgs: string[] = [];
        const full = input.full ?? defaults.full;
        if (full) {
            recordArgs.push('--full');
        }
        const count = input.count ?? defaults.count;
        if (count !== 2) {
            recordArgs.push(`--count=${count}`);
        }
        const interval = input.interval ?? defaults.interval;
        if (interval !== '5s') {
            recordArgs.push(`--interval=${interval}`);
        }

        const args = ['record', 'create', ...recordArgs, '-o', savePath, String(target)];
        const result = await runJstall(context, args, token);

        if (result.exitCode === 0) {
            return `Recording saved to ${savePath}. Use jstall_run with ` +
                `["status", "${savePath}"] to analyze it, or ` +
                `jstall_flamegraph with recordingZip to view its flamegraph.`;
        }

        throw new Error(`Recording failed: ${stripAnsi(result.stderr || result.stdout)}`);
    });

    // ── Remote ─────────────────────────────────────────────────────
    registerTool<{
        type: 'ssh' | 'cf';
        target: string;
        command: string;
        args?: string[];
    }>(context, 'jstall_remote', async (input, token) => {
        if (!input.type || !['ssh', 'cf'].includes(input.type)) {
            throw new Error('"type" is required and must be "ssh" or "cf".');
        }
        if (!input.target?.trim()) {
            throw new Error('"target" is required (SSH host like user@hostname, or CF app name).');
        }
        if (!input.command?.trim()) {
            throw new Error('"command" is required (e.g. "status", "threads", "deadlock").');
        }
        const flag = input.type === 'ssh' ? '--ssh' : '--cf';
        const flagValue = input.type === 'ssh' ? `ssh ${input.target.trim()}` : input.target.trim();
        const remoteArgs = [
            flag,
            flagValue,
            ...input.command.trim().split(/\s+/),
            ...(input.args ?? []),
        ];
        return jstallOutput(await runJstall(context, remoteArgs, token));
    });

    // ── Reveal Code ───────────────────────────────────────────────
    registerTool<{
        className: string;
        lineNumber: number;
        endLine?: number;
        message?: string;
        explanation?: string;
        severity?: 'error' | 'warning' | 'info';
        viewColumn?: 'beside' | 'one' | 'two' | 'three';
    }>(context, 'jstall_reveal_code', async (input) => {
        if (!input.className?.trim()) {
            throw new Error('"className" is required (fully-qualified Java class name, e.g. "com.example.OrderService").');
        }

        // Allow className to contain :lineNumber suffix (e.g. "com.example.Foo:42")
        let className = input.className.trim();
        let lineNumber = input.lineNumber;
        const colonMatch = /^(.+):(\d+)$/.exec(className);
        if (colonMatch) {
            className = colonMatch[1];
            if (!lineNumber) {
                lineNumber = parseInt(colonMatch[2], 10);
            }
        }

        if (!lineNumber || lineNumber < 1) {
            throw new Error('"lineNumber" must be a positive integer (1-based line number).');
        }

        const uri = await resolveJavaClassToFile(className);
        if (!uri) {
            throw new Error(
                `Could not find file for class "${className}" in the workspace. ` +
                'Make sure the Java source is part of an open workspace folder.'
            );
        }

        // Resolve viewColumn
        let viewColumn: vscode.ViewColumn | undefined;
        switch (input.viewColumn) {
            case 'beside': viewColumn = vscode.ViewColumn.Beside; break;
            case 'one': viewColumn = vscode.ViewColumn.One; break;
            case 'two': viewColumn = vscode.ViewColumn.Two; break;
            case 'three': viewColumn = vscode.ViewColumn.Three; break;
        }

        await revealCodeLocation(uri, lineNumber, {
            explanation: input.explanation,
            viewColumn,
            severity: input.severity,
            endLine: input.endLine,
        });

        if (input.message?.trim()) {
            vscode.window.showInformationMessage(input.message.trim());
        }

        const wsFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';
        const relativePath = uri.fsPath.startsWith(wsFolder)
            ? uri.fsPath.slice(wsFolder.length + 1)
            : uri.fsPath;

        const rangeStr = input.endLine ? `lines ${lineNumber}-${input.endLine}` : `line ${lineNumber}`;
        return `Opened ${className} at ${rangeStr} (${relativePath})${input.viewColumn ? ` in column ${input.viewColumn}` : ''}`;
    });

    // ── Reveal Code Batch ─────────────────────────────────────────
    registerTool<{
        locations: {
            className: string;
            lineNumber: number;
            endLine?: number;
            explanation?: string;
            severity?: 'error' | 'warning' | 'info';
            viewColumn?: 'beside' | 'one' | 'two' | 'three';
        }[];
        message?: string;
    }>(context, 'jstall_reveal_code_batch', async (input) => {
        if (!input.locations || !Array.isArray(input.locations) || input.locations.length === 0) {
            throw new Error('"locations" must be a non-empty array of reveal targets.');
        }

        const results: string[] = [];
        const wsFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? '';

        for (const loc of input.locations) {
            if (!loc.className?.trim()) {
                results.push('Skipped: missing className');
                continue;
            }

            let className = loc.className.trim();
            let lineNumber = loc.lineNumber;
            const colonMatch = /^(.+):(\d+)$/.exec(className);
            if (colonMatch) {
                className = colonMatch[1];
                if (!lineNumber) {
                    lineNumber = parseInt(colonMatch[2], 10);
                }
            }

            if (!lineNumber || lineNumber < 1) {
                results.push(`Skipped ${className}: invalid lineNumber`);
                continue;
            }

            const uri = await resolveJavaClassToFile(className);
            if (!uri) {
                results.push(`Skipped ${className}: not found in workspace`);
                continue;
            }

            let viewColumn: vscode.ViewColumn | undefined;
            switch (loc.viewColumn) {
                case 'beside': viewColumn = vscode.ViewColumn.Beside; break;
                case 'one': viewColumn = vscode.ViewColumn.One; break;
                case 'two': viewColumn = vscode.ViewColumn.Two; break;
                case 'three': viewColumn = vscode.ViewColumn.Three; break;
            }

            await revealCodeLocation(uri, lineNumber, {
                explanation: loc.explanation,
                viewColumn,
                severity: loc.severity,
                endLine: loc.endLine,
            });

            const relativePath = uri.fsPath.startsWith(wsFolder)
                ? uri.fsPath.slice(wsFolder.length + 1)
                : uri.fsPath;
            const rangeStr = loc.endLine ? `lines ${lineNumber}-${loc.endLine}` : `line ${lineNumber}`;
            results.push(`${className} at ${rangeStr} (${relativePath})`);
        }

        if (input.message?.trim()) {
            vscode.window.showInformationMessage(input.message.trim());
        }

        return `Revealed ${results.length} location(s):\n${results.join('\n')}`;
    });

    // ── Clear Highlights ──────────────────────────────────────────
    registerTool<Record<string, never>>(context, 'jstall_clear_highlights', () => {
        clearAllHighlights();
        return 'All JStall code highlights have been cleared.';
    });

    // ── Run Profiled ──────────────────────────────────────────────
    registerTool<{
        javaArgs: string[];
        event?: string;
        interval?: string;
        outputFile?: string;
        cwd?: string;
    }>(context, 'jstall_run_profiled', async (input, token) => {
        if (!input.javaArgs || input.javaArgs.length === 0) {
            throw new Error(
                '"javaArgs" is required. Provide the Java arguments as an array, ' +
                'e.g. ["-jar", "target/app.jar", "arg1"] or ["-cp", "classes", "com.example.Main"].'
            );
        }
        const result = await runJavaProfiled(context, {
            javaArgs: input.javaArgs,
            event: input.event,
            interval: input.interval,
            outputFile: input.outputFile,
            cwd: input.cwd,
        }, token);

        const output = stripAnsi(
            result.stdout + (result.stderr ? '\n' + result.stderr : '')
        ).trim();

        const lines: string[] = [];
        lines.push(`Process exited with code ${result.exitCode}.`);
        if (fs.existsSync(result.profileOutputFile)) {
            lines.push(`Profile saved to: ${result.profileOutputFile}`);
            lines.push(`Event: ${input.event || 'cpu'}`);
            // Return collapsed stacks content directly for LLM consumption
            if (result.profileOutputFile.endsWith('.collapsed')) {
                const content = fs.readFileSync(result.profileOutputFile, 'utf-8').trim();
                if (content) {
                    lines.push('');
                    lines.push('--- Collapsed stacks (stack;frame;frame count) ---');
                    lines.push(content);
                }
            }
        } else {
            lines.push('Warning: Profile output file was not created. The process may have exited too quickly.');
        }
        if (output) {
            lines.push('');
            lines.push('--- Process output ---');
            lines.push(output);
        }
        return lines.join('\n');
    });

    // ── Dependency Graph ──────────────────────────────────────────
    // Registered directly (not via registerTool helper) so we can
    // access options.toolInvocationToken and invoke renderMermaidDiagram
    // inline in the Copilot chat.
    context.subscriptions.push(
        vscode.lm.registerTool<{
            pid?: number | 'all';
            intelligentFilter?: boolean;
            recordingZip?: string;
        }>('jstall_dependency_graph', {
            async invoke(options, token) {
                try {
                    const input = options.input;
                    const args: string[] = ['dependency-graph'];

                    const defaults = getMcpStatusDefaults();
                    if (input.intelligentFilter ?? defaults.intelligentFilter) { args.push('--intelligent-filter'); }

                    if (input.recordingZip) {
                        args.push(input.recordingZip);
                    } else {
                        args.push(String(requireStatusOrRecordTarget(input)));
                    }

                    const result = await runJstall(context, args, token);
                    const raw = jstallOutput(result);

                    // Parse the raw output into a Mermaid graph
                    const mermaid = dependencyGraphToMermaid(raw);

                    // Render the Mermaid diagram inline via renderMermaidDiagram
                    if (mermaid) {
                        const renderTool = vscode.lm.tools.find(t => t.name === 'renderMermaidDiagram');
                        if (renderTool) {
                            try {
                                await vscode.lm.invokeTool('renderMermaidDiagram', {
                                    toolInvocationToken: options.toolInvocationToken,
                                    input: { markup: mermaid, title: 'Thread Dependency Graph' },
                                }, token);
                            } catch {
                                // Fall through — raw text is still returned below
                            }
                        }
                    }

                    return new vscode.LanguageModelToolResult([
                        new vscode.LanguageModelTextPart(raw),
                    ]);
                } catch (error: unknown) {
                    const msg = error instanceof Error ? error.message : String(error);
                    return new vscode.LanguageModelToolResult([
                        new vscode.LanguageModelTextPart(`Error: ${msg}`),
                    ]);
                }
            },
        }),
    );

    console.log('JStall: Registered 10 language model tools.');
}

// ─── Mermaid conversion ───────────────────────────────────────────

/**
 * Parses JStall dependency-graph text output and converts it to a
 * Mermaid flowchart showing threads as nodes and lock-wait edges.
 * Deadlock cycles are highlighted in red.
 */
function dependencyGraphToMermaid(raw: string): string | undefined {
    // Match lines like:
    //   [Category] ThreadName
    //     -> [Category] OwnerThreadName (lock: <0x...>)
    //        Waiter state: BLOCKED, CPU: 0.00s
    //        Owner state:  BLOCKED, CPU: 0.00s
    const edgeRegex = /^\[.*?]\s+(.+?)\s*$/;
    const arrowRegex = /^\s+->\s+\[.*?]\s+(.+?)\s+\(lock:\s+<([^>]+)>\)/;
    const waiterStateRegex = /^\s+Waiter state:\s+(\S+)/;

    interface Edge {
        waiter: string;
        owner: string;
        lock: string;
        waiterState?: string;
    }

    const edges: Edge[] = [];
    const lines = raw.split('\n');
    let currentWaiter: string | undefined;

    for (let i = 0; i < lines.length; i++) {
        const edgeMatch = edgeRegex.exec(lines[i]);
        if (edgeMatch && !lines[i].startsWith(' ') && !lines[i].startsWith('\t')) {
            currentWaiter = edgeMatch[1].trim();
            continue;
        }

        const arrowMatch = arrowRegex.exec(lines[i]);
        if (arrowMatch && currentWaiter) {
            const edge: Edge = {
                waiter: currentWaiter,
                owner: arrowMatch[1].trim(),
                lock: arrowMatch[2],
            };
            // Look ahead for waiter state
            if (i + 1 < lines.length) {
                const stateMatch = waiterStateRegex.exec(lines[i + 1]);
                if (stateMatch) {
                    edge.waiterState = stateMatch[1];
                }
            }
            edges.push(edge);
        }
    }

    if (edges.length === 0) {
        return undefined;
    }

    // Deduplicate edges (same waiter→owner pair, keep first lock)
    const seen = new Set<string>();
    const uniqueEdges: Edge[] = [];
    for (const e of edges) {
        const key = `${e.waiter}|${e.owner}`;
        if (!seen.has(key)) {
            seen.add(key);
            uniqueEdges.push(e);
        }
    }

    // Detect deadlock: find threads that are both waiters and owners
    const hasDeadlock = raw.includes('DEADLOCK');

    // Build Mermaid
    const mermaidLines: string[] = ['graph LR'];

    // Sanitize node names for Mermaid (replace spaces, special chars)
    const nodeId = (name: string) => name.replace(/[^a-zA-Z0-9]/g, '_');

    // Collect unique threads
    const threads = new Set<string>();
    for (const e of uniqueEdges) {
        threads.add(e.waiter);
        threads.add(e.owner);
    }

    // Node definitions
    for (const t of threads) {
        const id = nodeId(t);
        mermaidLines.push(`    ${id}["🧵 ${t}"]`);
    }

    // Edge definitions
    for (const e of uniqueEdges) {
        const lockShort = e.lock.length > 8 ? '...' + e.lock.slice(-6) : e.lock;
        mermaidLines.push(
            `    ${nodeId(e.waiter)} -->|"waits for lock ${lockShort}"| ${nodeId(e.owner)}`
        );
    }

    // Style deadlocked nodes red
    if (hasDeadlock) {
        for (const t of threads) {
            mermaidLines.push(`    style ${nodeId(t)} fill:#ff6b6b,stroke:#c0392b,color:#fff`);
        }
    }

    return mermaidLines.join('\n');
}
