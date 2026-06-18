/**
 * Recording file actions: replay, summary, flamegraph extraction, and ZIP extraction.
 * Mirrors the IntelliJ plugin's JStallReplayAction, JStallRecordingFlameAction, JStallExtractAction.
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';
import { runJstall, runJstallToOutput, getStatusArgs, stripAnsi } from './jstall';
import { showFlamegraph } from './flamegraphViewer';

// ─── Recording actions ─────────────────────────────────────────────

/**
 * Replay a recording: run `jstall status <zip>` with progress & cancellation.
 */
export async function replayRecording(
    context: vscode.ExtensionContext,
    zipPath: string,
    output: vscode.OutputChannel
): Promise<void> {
    output.show(true);
    output.appendLine(`Analyzing recording: ${path.basename(zipPath)}`);
    output.appendLine('─'.repeat(60));

    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Analyzing ${path.basename(zipPath)}...`, cancellable: true },
        async (_progress, token) => {
            const args = ['status', ...getStatusArgs(), zipPath];
            const exitCode = await runJstallToOutput(context, args, output, token);
            if (exitCode !== 0 && !token.isCancellationRequested) {
                output.appendLine(`\n[Exit code: ${exitCode}]`);
                vscode.window.showErrorMessage(
                    `Recording analysis failed (exit code ${exitCode}). ` +
                    'The file may not be a valid JStall recording.'
                );
            }
        }
    );
}

/**
 * Show recording summary: run `jstall record summary <zip>` and display in output.
 */
export async function showRecordingSummary(
    context: vscode.ExtensionContext,
    zipPath: string,
    output: vscode.OutputChannel
): Promise<void> {
    output.show(true);
    output.appendLine(`Recording summary: ${path.basename(zipPath)}`);
    output.appendLine('─'.repeat(60));

    // File size
    try {
        const stat = fs.statSync(zipPath);
        output.appendLine(`File: ${zipPath}`);
        output.appendLine(`Size: ${formatFileSize(stat.size)}`);
        output.appendLine('─'.repeat(60));
    } catch {}

    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'Loading summary...', cancellable: true },
        async (_progress, token) => {
            const args = ['record', 'summary', zipPath];
            const exitCode = await runJstallToOutput(context, args, output, token);
            if (exitCode !== 0 && !token.isCancellationRequested) {
                output.appendLine(`\n[Exit code: ${exitCode}]`);
                vscode.window.showErrorMessage(
                    `Recording summary failed (exit code ${exitCode}). ` +
                    'The file may not be a valid JStall recording.'
                );
            }
        }
    );
}

/**
 * Open a flamegraph from a recording ZIP.
 * Lists PIDs in the recording, lets user pick one if multiple, then extracts and displays.
 */
export async function showRecordingFlamegraph(
    context: vscode.ExtensionContext,
    zipPath: string,
    token?: vscode.CancellationToken
): Promise<void> {
    // List recorded JVMs
    const listResult = await runJstall(context, ['-f', zipPath, 'list'], token);
    if (token?.isCancellationRequested) { return; }
    if (listResult.exitCode !== 0) {
        vscode.window.showErrorMessage(
            'Could not read recording. The file may not be a valid JStall recording. ' +
            stripAnsi(listResult.stderr || listResult.stdout)
        );
        return;
    }

    const pids = listResult.stdout
        .split('\n')
        .map(l => l.trim())
        .filter(l => l.length > 0)
        .map(l => {
            const match = /^(\d+)\s+(.+)$/.exec(l);
            return match ? { pid: match[1], name: match[2] } : null;
        })
        .filter((p): p is { pid: string; name: string } => p !== null);

    if (pids.length === 0) {
        vscode.window.showWarningMessage('No JVMs found in recording.');
        return;
    }

    // Pick PID if multiple
    let pid = pids[0].pid;
    if (pids.length > 1) {
        const pick = await vscode.window.showQuickPick(
            pids.map(p => ({ label: p.pid, description: p.name })),
            { placeHolder: 'Select JVM for flamegraph' }
        );
        if (!pick) { return; }
        pid = pick.label;
    }

    // Extract flamegraph to temp file
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jstall-flame-'));
    const flameFile = path.join(tmpDir, 'flame.html');

    try {
        const result = await runJstall(context, ['-f', zipPath, 'flame', pid, '-o', flameFile], token);

        if (result.exitCode !== 0 || !fs.existsSync(flameFile)) {
            vscode.window.showErrorMessage(
                'No flamegraph available in this recording. ' +
                'The recording might not contain profiling data for this JVM.'
            );
            return;
        }

        const html = fs.readFileSync(flameFile, 'utf8');
        showFlamegraph(html, `Flamegraph: ${path.basename(zipPath)}`);
    } finally {
        // Cleanup temp dir
        try { fs.rmSync(tmpDir, { recursive: true }); } catch {}
    }
}

/**
 * Extract a recording ZIP to a user-selected folder.
 * Uses `jstall record extract <zip> <outputDir>`.
 */
export async function extractRecording(
    context: vscode.ExtensionContext,
    zipPath: string
): Promise<void> {
    const defaultUri = vscode.Uri.file(path.dirname(zipPath));
    const outputUri = await vscode.window.showOpenDialog({
        defaultUri,
        canSelectFiles: false,
        canSelectFolders: true,
        canSelectMany: false,
        title: 'Select folder to extract recording into',
    });

    if (!outputUri || outputUri.length === 0) { return; }

    const outputDir = outputUri[0].fsPath;

    const result = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'Extracting recording...', cancellable: false },
        () => runJstall(context, ['record', 'extract', zipPath, outputDir])
    );

    if (result.exitCode === 0) {
        const revealLabel = platformRevealLabel();
        const action = await vscode.window.showInformationMessage(
            `Recording extracted to ${outputDir}`,
            'Open Folder',
            revealLabel
        );
        if (action === 'Open Folder') {
            await vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.file(outputDir), true);
        } else if (action === revealLabel) {
            await vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(outputDir));
        }
    } else {
        vscode.window.showErrorMessage(
            `Extraction failed: ${stripAnsi(result.stderr || result.stdout)}`
        );
    }
}

// ─── Helpers ───────────────────────────────────────────────────────

/** Platform-appropriate label for the "reveal in file manager" action. */
export function platformRevealLabel(): string {
    return process.platform === 'darwin' ? 'Reveal in Finder'
        : process.platform === 'win32' ? 'Reveal in Explorer' : 'Reveal in Files';
}

/** Format bytes as human-readable size. */
export function formatFileSize(bytes: number): string {
    if (bytes < 1024) { return `${bytes} B`; }
    const kb = bytes / 1024;
    if (kb < 1024) { return `${kb.toFixed(1)} KB`; }
    const mb = kb / 1024;
    if (mb < 1024) { return `${mb.toFixed(1)} MB`; }
    const gb = mb / 1024;
    return `${gb.toFixed(2)} GB`;
}
