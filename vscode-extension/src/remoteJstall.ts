/**
 * JStall Remote — connect to remote JVMs via SSH or Cloud Foundry
 * and run any supported jstall command.
 */

import * as vscode from 'vscode';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';
import { runJstall, runJstallToOutput, stripAnsi, getStatusArgs, getFlameArgs, getRecordArgs, errorMessage } from './jstall';
import { showFlamegraph } from './flamegraphViewer';

type RemoteType = 'ssh' | 'cf';

interface RemoteCommandItem extends vscode.QuickPickItem {
    command: string;
}

const REMOTE_COMMANDS: RemoteCommandItem[] = [
    { label: '$(list-unordered) List JVMs',  description: 'List running JVM processes',     command: 'list' },
    { label: '$(pulse) Status',              description: 'Thread dumps & diagnostics',    command: 'status' },
    { label: '$(flame) Flamegraph',          description: 'CPU profiling flamegraph',       command: 'flame' },
    { label: '$(record) Record',             description: 'Full diagnostic recording',      command: 'record create' },
    { label: '$(list-tree) Threads',         description: 'Thread listing',                 command: 'threads' },
    { label: '$(warning) Deadlock',          description: 'Deadlock detection',             command: 'deadlock' },
    { label: '$(graph) Most Work',           description: 'Top busy threads',               command: 'most-work' },
    { label: '$(clock) Waiting Threads',     description: 'Threads in waiting state',       command: 'waiting-threads' },
    { label: '$(type-hierarchy) Dependency Graph', description: 'Thread dependency graph',  command: 'dependency-graph' },
    { label: '$(dashboard) VM Vitals',       description: 'VM performance counters',        command: 'vm-vitals' },
    { label: '$(server) GC & Heap Info',     description: 'Garbage collection & heap info', command: 'gc-heap-info' },
    { label: '$(terminal) Custom Command…',  description: 'Run any jstall command',         command: '' },
];

const STATE_LAST_TYPE       = 'jstall.remote.lastType';
const STATE_LAST_SSH_TARGET = 'jstall.remote.lastSshTarget';
const STATE_LAST_CF_TARGET  = 'jstall.remote.lastCfTarget';

// ─── Public entry point ───────────────────────────────────────────

export async function runRemoteCommand(
    context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel,
): Promise<void> {
    // 1. Pick connection type (pre-select last used)
    const lastType = context.globalState.get<RemoteType>(STATE_LAST_TYPE, 'ssh');
    const typeItems: vscode.QuickPickItem[] = [
        { label: 'SSH', description: 'Connect via SSH to a remote host' },
        { label: 'CF',  description: 'Connect to a Cloud Foundry application' },
    ];
    const typePick = await vscode.window.showQuickPick(typeItems, {
        placeHolder: lastType === 'cf' ? 'CF (last used)' : 'SSH (last used)',
        title: 'JStall Remote',
    });
    if (!typePick) { return; }
    const remoteType = typePick.label.toLowerCase() as RemoteType;

    // 2. Pick or enter target
    const lastTargetKey = remoteType === 'ssh' ? STATE_LAST_SSH_TARGET : STATE_LAST_CF_TARGET;
    const lastTarget = context.globalState.get<string>(lastTargetKey, '');
    const target = await pickOrEnterTarget(remoteType, lastTarget);
    if (!target) { return; }

    // 3. Pick command
    const commandPick = await vscode.window.showQuickPick(REMOTE_COMMANDS, {
        placeHolder: 'Select command to run',
        title: `JStall Remote: ${remoteType.toUpperCase()} ${target}`,
    });
    if (!commandPick) { return; }

    let commandArgs: string[];
    if (commandPick.command === '') {
        const customArgs = await vscode.window.showInputBox({
            prompt: 'Enter jstall command and arguments (e.g., "status --full")',
            placeHolder: 'status --full',
            title: 'JStall Remote: Custom Command',
        });
        if (!customArgs?.trim()) { return; }
        commandArgs = customArgs.trim().split(/\s+/);
    } else {
        commandArgs = commandPick.command.split(' ');
        if (commandArgs[0] === 'status')  { commandArgs.push(...getStatusArgs()); }
        if (commandArgs[0] === 'flame')   { commandArgs.push(...getFlameArgs()); }
        if (commandArgs[0] === 'record')  { commandArgs.push(...getRecordArgs()); }
    }

    // Persist last-used connection
    await context.globalState.update(STATE_LAST_TYPE, remoteType);
    await context.globalState.update(lastTargetKey, target);

    // 4. Execute
    try {
        if (commandArgs[0] === 'flame') {
            await runRemoteFlame(context, remoteType, target, commandArgs);
        } else {
            await runRemoteGeneric(context, remoteType, target, commandArgs, outputChannel);
        }
    } catch (err: unknown) {
        vscode.window.showErrorMessage(`JStall Remote failed: ${errorMessage(err)}`);
    }
}

// ─── Internals ────────────────────────────────────────────────────

async function runRemoteGeneric(
    context: vscode.ExtensionContext,
    type: RemoteType,
    target: string,
    commandArgs: string[],
    outputChannel: vscode.OutputChannel,
): Promise<void> {
    outputChannel.clear();
    outputChannel.show(true);
    outputChannel.appendLine(`JStall Remote [${type.toUpperCase()}] → ${target}`);
    outputChannel.appendLine(`Command: ${commandArgs.join(' ')}`);
    outputChannel.appendLine('─'.repeat(60));

    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `JStall Remote: ${commandArgs[0]}…`, cancellable: true },
        async (_progress, token) => {
            const args = buildRemoteArgs(type, target, commandArgs);
            await runJstallToOutput(context, args, outputChannel, token);
        },
    );
}

async function runRemoteFlame(
    context: vscode.ExtensionContext,
    type: RemoteType,
    target: string,
    commandArgs: string[],
): Promise<void> {
    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'JStall Remote: Capturing flamegraph…', cancellable: true },
        async (_progress, token) => {
            const tmpFile = path.join(os.tmpdir(), `jstall-remote-flame-${Date.now()}.html`);
            try {
                const args = [...buildRemoteArgs(type, target, commandArgs), '-o', tmpFile];
                const result = await runJstall(context, args, token);

                if (result.exitCode === 0 && fs.existsSync(tmpFile)) {
                    const html = fs.readFileSync(tmpFile, 'utf8');
                    showFlamegraph(html, `Flamegraph: ${type.toUpperCase()} ${target}`);
                } else if (!token.isCancellationRequested) {
                    vscode.window.showErrorMessage(
                        `Remote flamegraph failed: ${stripAnsi(result.stderr || result.stdout)}`,
                    );
                }
            } finally {
                try { fs.unlinkSync(tmpFile); } catch { /* ignore */ }
            }
        },
    );
}

function buildRemoteArgs(type: RemoteType, target: string, commandArgs: string[]): string[] {
    const flag = type === 'ssh' ? '--ssh' : '--cf';
    const value = type === 'ssh' ? `ssh ${target}` : target;
    return [flag, value, ...commandArgs];
}

async function pickOrEnterTarget(type: RemoteType, lastTarget: string): Promise<string | undefined> {
    const config = vscode.workspace.getConfiguration('jstall');
    const saved: string[] = type === 'ssh'
        ? config.get<string[]>('remote.sshHosts', [])
        : config.get<string[]>('remote.cfApps', []);

    // De-duplicate saved entries with lastTarget on top
    const unique = [...new Set([lastTarget, ...saved].map(s => s.trim()).filter(Boolean))];

    if (unique.length > 0) {
        const NEW_LABEL = '$(add) Enter new…';
        const items: vscode.QuickPickItem[] = [
            ...unique.map(h => ({
                label: h,
                description: h === lastTarget ? '(last used)' : undefined,
            })),
            { label: NEW_LABEL, kind: vscode.QuickPickItemKind.Separator },
            { label: NEW_LABEL },
        ];
        const pick = await vscode.window.showQuickPick(items, {
            placeHolder: type === 'ssh' ? 'Select SSH host' : 'Select CF app',
            title: 'JStall Remote',
        });
        if (!pick) { return undefined; }
        if (pick.label !== NEW_LABEL) { return pick.label; }
    }

    return vscode.window.showInputBox({
        prompt: type === 'ssh'
            ? 'Enter SSH connection (e.g., user@hostname)'
            : 'Enter Cloud Foundry app name',
        value: lastTarget,
        placeHolder: type === 'ssh' ? 'user@hostname' : 'my-app-name',
        title: 'JStall Remote',
        validateInput: (v) => v.trim() ? undefined : 'Target is required',
    });
}
