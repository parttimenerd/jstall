import * as vscode from 'vscode';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';
import { findJava17Plus, resetJavaCache, runJstall, runJstallToOutput, getStatusArgs, getFlameArgs, getRecordArgs, stripAnsi, getJarPath, resolveRecordingSavePath, errorMessage } from './jstall';
import { JvmProcess, isProcessAlive } from './jvmPicker';
import { showFlamegraph } from './flamegraphViewer';
import { replayRecording, showRecordingFlamegraph, extractRecording, showRecordingSummary, formatFileSize, platformRevealLabel } from './recordingHandler';
import { initDebugIntegration, getActiveJvm, pickJvmWithDebugHint, disposeDebugIntegration } from './debugIntegration';
import { registerMcpTools } from './mcpTools';
import { runRemoteCommand } from './remoteJstall';
import { clearAllHighlights, onDidChangeVisibleEditors } from './codeNavigation';

let outputChannel: vscode.OutputChannel;

// ─── Shared JVM action helpers (used by both picker & active commands) ──

async function runStatusOnJvm(context: vscode.ExtensionContext, jvm: JvmProcess): Promise<void> {
    outputChannel.clear();
    outputChannel.show(true);
    outputChannel.appendLine(`JStall Status: PID ${jvm.pid} (${jvm.name})`);
    outputChannel.appendLine('─'.repeat(60));

    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Analyzing JVM ${jvm.pid}...`, cancellable: true },
        async (_progress, token) => {
            const args = ['status', ...getStatusArgs(), String(jvm.pid)];
            await runJstallToOutput(context, args, outputChannel, token);
        }
    );
}

async function runFlameOnJvm(context: vscode.ExtensionContext, jvm: JvmProcess): Promise<void> {
    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Capturing flamegraph for JVM ${jvm.pid}...`, cancellable: true },
        async (_progress, token) => {
            const tmpFile = path.join(os.tmpdir(), `jstall-flame-${Date.now()}.html`);
            try {
                const args = ['flame', ...getFlameArgs(), '-o', tmpFile, String(jvm.pid)];
                const result = await runJstall(context, args, token);

                if (result.exitCode === 0 && fs.existsSync(tmpFile)) {
                    const html = fs.readFileSync(tmpFile, 'utf8');
                    showFlamegraph(html, `Flamegraph: ${jvm.name} (${jvm.pid})`);
                } else if (!token.isCancellationRequested) {
                    vscode.window.showErrorMessage(
                        `Flamegraph failed: ${stripAnsi(result.stderr || result.stdout)}`
                    );
                }
            } finally {
                try { fs.unlinkSync(tmpFile); } catch {}
            }
        }
    );
}

async function runRecordOnJvm(context: vscode.ExtensionContext, jvm: JvmProcess): Promise<void> {
    const { savePath, fileName } = resolveRecordingSavePath(jvm.pid);

    outputChannel.clear();
    outputChannel.show(true);
    outputChannel.appendLine(`Recording JVM ${jvm.pid} (${jvm.name})...`);
    outputChannel.appendLine(`Output: ${savePath}`);
    outputChannel.appendLine('─'.repeat(60));

    let cancelled = false;
    const exitCode = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Recording JVM ${jvm.pid}...`, cancellable: true },
        async (_progress, token) => {
            const args = ['record', 'create', ...getRecordArgs(), '-o', savePath, String(jvm.pid)];
            const code = await runJstallToOutput(context, args, outputChannel, token);
            cancelled = token.isCancellationRequested;
            return code;
        }
    );

    if (cancelled) { return; }

    if (exitCode === 0 && fs.existsSync(savePath)) {
        const stat = fs.statSync(savePath);
        const sizeStr = formatFileSize(stat.size);
        const saveUri = vscode.Uri.file(savePath);
        const revealLabel = platformRevealLabel();
        const action = await vscode.window.showInformationMessage(
            `Recording saved (${sizeStr}): ${fileName}`,
            'Replay', 'Flamegraph', revealLabel
        );
        if (action === 'Replay') {
            await replayRecording(context, savePath, outputChannel);
        } else if (action === 'Flamegraph') {
            await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Notification, title: 'Loading flamegraph...', cancellable: true },
                (_progress, token) => showRecordingFlamegraph(context, savePath, token)
            );
        } else if (action === revealLabel) {
            await vscode.commands.executeCommand('revealFileInOS', saveUri);
        }
    } else {
        vscode.window.showErrorMessage('Recording failed. Check JStall output for details.');
    }
}

/** Validate that a JVM is still alive before running a command. */
function validateJvm(jvm: JvmProcess): boolean {
    if (!isProcessAlive(jvm.pid)) {
        vscode.window.showWarningMessage(`JVM ${jvm.pid} (${jvm.name}) is no longer running.`);
        return false;
    }
    return true;
}

// ─── Activation ───────────────────────────────────────────────────

export function activate(context: vscode.ExtensionContext) {
    outputChannel = vscode.window.createOutputChannel('JStall');
    context.subscriptions.push(outputChannel);

    // Check JAR exists
    const jarPath = getJarPath(context);
    if (!fs.existsSync(jarPath)) {
        vscode.window.showWarningMessage(
            'JStall JAR not found. Run "npm run download-jar" in the extension directory.'
        );
    }

    // Warm-up: try to find Java early (non-blocking)
    findJava17Plus().then(
        (java) => console.log(`JStall: Using Java at ${java}`),
        (err: unknown) => console.warn(`JStall: ${errorMessage(err)}`)
    );

    // Reset cached Java path when jstall.javaPath setting changes
    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('jstall.javaPath')) {
                resetJavaCache();
            }
        })
    );

    // ─── Debug / Run / Terminal integration ────────────────────────
    initDebugIntegration(context);

    // ─── Live JVM commands (with picker) ───────────────────────────

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.status', async () => {
            try {
                const jvm = await pickJvmWithDebugHint(context);
                if (!jvm || !validateJvm(jvm)) { return; }
                await runStatusOnJvm(context, jvm);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Status failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.flame', async () => {
            try {
                const jvm = await pickJvmWithDebugHint(context);
                if (!jvm || !validateJvm(jvm)) { return; }
                await runFlameOnJvm(context, jvm);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Flamegraph failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.record', async () => {
            try {
                const jvm = await pickJvmWithDebugHint(context);
                if (!jvm || !validateJvm(jvm)) { return; }
                await runRecordOnJvm(context, jvm);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Record failed: ${errorMessage(err)}`);
            }
        })
    );

    // ─── Active-JVM quick-action commands ──────────────────────────

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.quickActions', async () => {
            const actions = [
                { label: '$(pulse) Status', description: 'Thread dumps & diagnostics', command: 'jstall.status' },
                { label: '$(flame) Flamegraph', description: 'CPU profiling flamegraph', command: 'jstall.flame' },
                { label: '$(record) Record', description: 'Full diagnostic recording', command: 'jstall.record' },
                { label: '$(remote) Remote', description: 'Connect to remote JVM (SSH / CF)', command: 'jstall.remote' },
            ];
            const pick = await vscode.window.showQuickPick(actions, { placeHolder: 'JStall: Choose action' });
            if (pick) { await vscode.commands.executeCommand(pick.command); }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.statusActive', async () => {
            const jvm = getActiveJvm();
            if (!jvm) { await vscode.commands.executeCommand('jstall.status'); return; }
            try {
                if (!validateJvm(jvm)) { return; }
                await runStatusOnJvm(context, jvm);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Status failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.flameActive', async () => {
            const jvm = getActiveJvm();
            if (!jvm) { await vscode.commands.executeCommand('jstall.flame'); return; }
            try {
                if (!validateJvm(jvm)) { return; }
                await runFlameOnJvm(context, jvm);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Flamegraph failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.recordActive', async () => {
            const jvm = getActiveJvm();
            if (!jvm) { await vscode.commands.executeCommand('jstall.record'); return; }
            try {
                if (!validateJvm(jvm)) { return; }
                await runRecordOnJvm(context, jvm);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Record failed: ${errorMessage(err)}`);
            }
        })
    );

    // ─── Remote JVM command ──────────────────────────────────────

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.remote', async () => {
            try {
                await runRemoteCommand(context, outputChannel);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Remote failed: ${errorMessage(err)}`);
            }
        })
    );

    // ─── Recording file commands (context menu + command palette) ──

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.replay', async (uri?: vscode.Uri) => {
            try {
                uri = uri ?? await pickZipFile();
                if (!uri) { return; }
                outputChannel.clear();
                await replayRecording(context, uri.fsPath, outputChannel);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Replay failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.summary', async (uri?: vscode.Uri) => {
            try {
                uri = uri ?? await pickZipFile();
                if (!uri) { return; }
                outputChannel.clear();
                await showRecordingSummary(context, uri.fsPath, outputChannel);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Summary failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.replayFlame', async (uri?: vscode.Uri) => {
            try {
                uri = uri ?? await pickZipFile();
                if (!uri) { return; }
                await vscode.window.withProgress(
                    { location: vscode.ProgressLocation.Notification, title: 'Loading flamegraph...', cancellable: true },
                    (_progress, token) => showRecordingFlamegraph(context, uri!.fsPath, token)
                );
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Flamegraph failed: ${errorMessage(err)}`);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.extract', async (uri?: vscode.Uri) => {
            try {
                uri = uri ?? await pickZipFile();
                if (!uri) { return; }
                await extractRecording(context, uri.fsPath);
            } catch (err: unknown) {
                vscode.window.showErrorMessage(`JStall Extract failed: ${errorMessage(err)}`);
            }
        })
    );

    // ─── Code Navigation (highlights) ───────────────────────────────
    context.subscriptions.push(
        vscode.commands.registerCommand('jstall.clearHighlights', () => {
            clearAllHighlights();
        })
    );
    context.subscriptions.push(
        vscode.window.onDidChangeVisibleTextEditors(onDidChangeVisibleEditors)
    );

    // ─── Language Model Tools (MCP) ────────────────────────────────
    registerMcpTools(context);
}

async function pickZipFile(): Promise<vscode.Uri | undefined> {
    const uris = await vscode.window.showOpenDialog({
        canSelectFiles: true,
        canSelectFolders: false,
        canSelectMany: false,
        filters: { 'JStall Recordings': ['zip'] },
        title: 'Select JStall Recording',
    });
    return uris?.[0];
}

export function deactivate() {
    disposeDebugIntegration();
}
