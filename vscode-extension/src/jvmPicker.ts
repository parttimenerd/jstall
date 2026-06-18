/**
 * JVM process picker — lists running JVMs via `jstall list` and shows a QuickPick.
 */

import * as vscode from 'vscode';
import { runJstall } from './jstall';

export interface JvmProcess {
    pid: number;
    name: string;
}

/**
 * List running JVM processes by invoking `jstall list`.
 */
export async function listJvms(context: vscode.ExtensionContext): Promise<JvmProcess[]> {
    const result = await runJstall(context, ['list']);
    if (result.exitCode !== 0) {
        return [];
    }

    const jvms: JvmProcess[] = [];
    for (const line of result.stdout.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed) { continue; }
        // jstall list output format: "%5d %s" → "  PID MainClass"
        const match = /^(\d+)\s+(.+)$/.exec(trimmed);
        if (match) {
            jvms.push({ pid: parseInt(match[1], 10), name: match[2] });
        }
    }
    return jvms;
}

/**
 * Show a QuickPick for the user to select a JVM process.
 */
export async function pickJvm(context: vscode.ExtensionContext): Promise<JvmProcess | undefined> {
    const jvms = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Window, title: 'Discovering JVMs...' },
        () => listJvms(context)
    );

    if (jvms.length === 0) {
        vscode.window.showWarningMessage('No JVM processes found.');
        return undefined;
    }

    const items = jvms.map(jvm => ({
        label: `$(vm) ${jvm.pid}`,
        description: jvm.name,
        jvm,
    }));

    const picked = await vscode.window.showQuickPick(items, {
        placeHolder: 'Select a JVM process',
        title: 'JStall: Select JVM',
    });

    return picked?.jvm;
}

/**
 * Check if a process with the given PID is still alive.
 * Uses a zero-signal kill which checks existence without sending a signal.
 */
export function isProcessAlive(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch {
        return false;
    }
}
