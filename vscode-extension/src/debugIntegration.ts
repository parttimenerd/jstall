/**
 * Debug & Run session integration — auto-detects JVM PIDs from active
 * debug sessions, "Run without Debugging" sessions, and terminal-launched
 * Java processes.  Provides a status bar item with JStall quick-action buttons.
 */

import * as vscode from 'vscode';
import { JvmProcess, listJvms, pickJvm } from './jvmPicker';

let statusBarItem: vscode.StatusBarItem;
let statusButton: vscode.StatusBarItem;
let flameButton: vscode.StatusBarItem;
let recordButton: vscode.StatusBarItem;

let activeJvmPid: number | undefined;
let activeJvmName: string | undefined;
/** Tracks origin: 'debug' | 'run' | 'terminal' */
let activeJvmSource: string | undefined;

/** PIDs snapshot taken just before a run/debug session starts */
let preRunPidSnapshot = new Set<number>();

// ─── Terminal watcher state ────────────────────────────────────────
let terminalWatcherInterval: ReturnType<typeof setInterval> | undefined;

/**
 * Initialize the debug/run session watcher, terminal watcher, and status bar buttons.
 */
export function initDebugIntegration(context: vscode.ExtensionContext): void {
    // ── Status bar items (left-aligned, shown as a group) ──────────
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 54);
    statusBarItem.command = 'jstall.quickActions';
    statusBarItem.tooltip = 'JStall: Click for JVM diagnostics';
    context.subscriptions.push(statusBarItem);

    statusButton = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 53);
    statusButton.text = '$(pulse) Status';
    statusButton.command = 'jstall.statusActive';
    statusButton.tooltip = 'JStall: Status of running JVM';
    context.subscriptions.push(statusButton);

    flameButton = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 52);
    flameButton.text = '$(flame) Flame';
    flameButton.command = 'jstall.flameActive';
    flameButton.tooltip = 'JStall: Flamegraph of running JVM';
    context.subscriptions.push(flameButton);

    recordButton = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 51);
    recordButton.text = '$(record) Record';
    recordButton.command = 'jstall.recordActive';
    recordButton.tooltip = 'JStall: Record running JVM';
    context.subscriptions.push(recordButton);

    // ── Debug / Run session lifecycle ──────────────────────────────
    context.subscriptions.push(
        vscode.debug.onDidStartDebugSession(session => {
            if (isJavaSession(session)) {
                const noDebug = (session.configuration as Record<string, unknown>)?.noDebug;
                const mode = noDebug ? 'run' : 'debug';
                void onJavaSessionStart(context, session, mode);
            }
        })
    );

    context.subscriptions.push(
        vscode.debug.onDidTerminateDebugSession(session => {
            if (isJavaSession(session)) {
                clearActiveJvm();
            }
        })
    );

    // Snapshot running PIDs before a launch so we can diff afterwards
    context.subscriptions.push(
        vscode.debug.onDidChangeActiveDebugSession(async () => {
            try {
                const jvms = await listJvms(context);
                preRunPidSnapshot = new Set(jvms.map(j => j.pid));
            } catch { /* ignore */ }
        })
    );

    // If there's already an active Java session at activation time
    if (vscode.debug.activeDebugSession && isJavaSession(vscode.debug.activeDebugSession)) {
        const cfg = vscode.debug.activeDebugSession.configuration as Record<string, unknown>;
        const mode = cfg?.noDebug ? 'run' : 'debug';
        void onJavaSessionStart(context, vscode.debug.activeDebugSession, mode);
    }

    // ── Terminal watcher ───────────────────────────────────────────
    // Poll for new Java processes launched from terminals when no
    // debug/run session is providing the PID already.
    context.subscriptions.push(
        vscode.window.onDidOpenTerminal(() => startTerminalWatcher(context))
    );
    context.subscriptions.push(
        vscode.window.onDidCloseTerminal(() => {
            if (vscode.window.terminals.length === 0) {
                stopTerminalWatcher();
            }
        })
    );
    // Start immediately if there are already terminals open
    if (vscode.window.terminals.length > 0) {
        startTerminalWatcher(context);
    }
}

function isJavaSession(session: vscode.DebugSession): boolean {
    return session.type === 'java'
        || session.type === 'javadbg'
        || session.type === 'kotlin'
        || session.type === 'java+';
}

// ─── Session start handler ─────────────────────────────────────────

async function onJavaSessionStart(
    context: vscode.ExtensionContext,
    session: vscode.DebugSession,
    mode: 'debug' | 'run'
): Promise<void> {
    // Give the JVM a moment to fully start
    await new Promise(resolve => setTimeout(resolve, 1500));

    try {
        const jvms = await listJvms(context);
        if (jvms.length === 0) { return; }

        // Try to match by launch config name / main class
        const cfg = session.configuration as Record<string, unknown> | undefined;
        const rawName = cfg?.mainClass ?? cfg?.program ?? session.name ?? '';
        const launchName = typeof rawName === 'string' ? rawName : '';

        let matched = jvms.find(jvm => {
            const name = jvm.name.toLowerCase();
            return launchName && name.includes(launchName.toLowerCase().split('.').pop() ?? '');
        });

        // Try diff approach — find the PID that wasn't in the pre-run snapshot
        if (!matched && preRunPidSnapshot.size > 0) {
            const newJvms = jvms.filter(j => !preRunPidSnapshot.has(j.pid));
            if (newJvms.length === 1) {
                matched = newJvms[0];
            }
        }

        // Fallback: highest PID heuristic
        if (!matched) {
            matched = jvms.reduce((a, b) => a.pid > b.pid ? a : b);
        }

        setActiveJvm(matched.pid, matched.name, mode);
    } catch {
        // JVM discovery failed
    }
}

// ─── Terminal watcher ──────────────────────────────────────────────

function startTerminalWatcher(context: vscode.ExtensionContext): void {
    if (terminalWatcherInterval) { return; }

    let knownPids = new Set<number>();

    // eslint-disable-next-line @typescript-eslint/no-misused-promises
    terminalWatcherInterval = setInterval(async () => {
        // Only poll when no session-based JVM is active
        if (activeJvmSource === 'debug' || activeJvmSource === 'run') { return; }

        try {
            const jvms = await listJvms(context);
            const currentPids = new Set(jvms.map(j => j.pid));

            // Find newly appeared JVMs
            for (const jvm of jvms) {
                if (!knownPids.has(jvm.pid)) {
                    // New JVM appeared — set as active
                    setActiveJvm(jvm.pid, jvm.name, 'terminal');
                    break;
                }
            }

            // If the active terminal-tracked JVM is gone, clear it
            if (activeJvmSource === 'terminal' && activeJvmPid && !currentPids.has(activeJvmPid)) {
                clearActiveJvm();
            }

            knownPids = currentPids;
        } catch { /* ignore */ }
    }, 3000);
}

function stopTerminalWatcher(): void {
    if (terminalWatcherInterval) {
        clearInterval(terminalWatcherInterval);
        terminalWatcherInterval = undefined;
    }
}

// ─── Active JVM management ─────────────────────────────────────────

function setActiveJvm(pid: number, name: string, source: string): void {
    activeJvmPid = pid;
    activeJvmName = name;
    activeJvmSource = source;

    void vscode.commands.executeCommand('setContext', 'jstall:hasActiveJvm', true);

    const icon = source === 'debug' ? '$(debug)' : source === 'run' ? '$(play)' : '$(terminal)';
    const shortName = name.split('.').pop() || name;
    statusBarItem.text = `${icon} JStall: ${shortName} (${pid})`;
    statusBarItem.show();
    statusButton.show();
    flameButton.show();
    recordButton.show();
}

function clearActiveJvm(): void {
    activeJvmPid = undefined;
    activeJvmName = undefined;
    activeJvmSource = undefined;

    void vscode.commands.executeCommand('setContext', 'jstall:hasActiveJvm', false);
    statusBarItem.hide();
    statusButton.hide();
    flameButton.hide();
    recordButton.hide();
}

/**
 * Returns the currently tracked JVM (from debug, run, or terminal).
 */
export function getActiveJvm(): JvmProcess | undefined {
    if (activeJvmPid !== undefined && activeJvmName !== undefined) {
        return { pid: activeJvmPid, name: activeJvmName };
    }
    return undefined;
}

/**
 * Pick a JVM, but prefer the active (debug/run/terminal) JVM if one is tracked.
 */
export async function pickJvmWithDebugHint(context: vscode.ExtensionContext): Promise<JvmProcess | undefined> {
    const activeJvm = getActiveJvm();

    if (activeJvm) {
        const sourceLabel = activeJvmSource === 'debug' ? 'debug session'
            : activeJvmSource === 'run' ? 'run session'
            : 'terminal';
        const icon = activeJvmSource === 'debug' ? '$(debug)'
            : activeJvmSource === 'run' ? '$(play)'
            : '$(terminal)';

        const choice = await vscode.window.showQuickPick(
            [
                {
                    label: `${icon} ${activeJvm.pid}`,
                    description: `${activeJvm.name} (current ${sourceLabel})`,
                    jvm: activeJvm,
                },
                {
                    label: '$(list-unordered) Pick another JVM...',
                    description: 'Show all running JVMs',
                    jvm: undefined as JvmProcess | undefined,
                },
            ],
            { placeHolder: 'Select JVM for JStall' }
        );

        if (!choice) { return undefined; }
        if (choice.jvm) { return choice.jvm; }
    }

    return pickJvm(context);
}

/**
 * Dispose the terminal watcher on deactivate.
 */
export function disposeDebugIntegration(): void {
    stopTerminalWatcher();
    clearActiveJvm();
}
