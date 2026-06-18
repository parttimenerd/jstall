/**
 * WebView-based flamegraph viewer.
 * Displays flamegraph HTML (from async-profiler) inside VS Code panels.
 * Each flamegraph opens in its own tab for side-by-side comparison.
 * Includes a "Save" action to export the HTML to a file.
 */

import * as vscode from 'vscode';
import * as fs from 'fs';

const flamegraphHtmlByPanel = new WeakMap<vscode.WebviewPanel, string>();
let activeFlamegraphPanel: vscode.WebviewPanel | undefined;

/**
 * Show a flamegraph HTML in a new WebView panel.
 * Each invocation creates a new tab.
 */
export function showFlamegraph(html: string, title = 'JStall Flamegraph'): void {
    const panel = vscode.window.createWebviewPanel(
        'jstallFlamegraph',
        title,
        vscode.ViewColumn.One,
        {
            enableScripts: true,
            retainContextWhenHidden: true,
        }
    );

    panel.webview.html = html;
    flamegraphHtmlByPanel.set(panel, html);
    activeFlamegraphPanel = panel;

    panel.onDidChangeViewState((e) => {
        if (e.webviewPanel.active) {
            activeFlamegraphPanel = e.webviewPanel;
        }
    });

    panel.onDidDispose(() => {
        if (activeFlamegraphPanel === panel) {
            activeFlamegraphPanel = undefined;
        }
    });
}

export async function saveActiveFlamegraph(): Promise<void> {
    const panel = activeFlamegraphPanel;
    if (!panel) {
        vscode.window.showWarningMessage('No active flamegraph tab to save.');
        return;
    }

    const html = flamegraphHtmlByPanel.get(panel);
    if (!html) {
        vscode.window.showWarningMessage('Unable to find flamegraph content to save.');
        return;
    }

    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    const defaultUri = workspaceFolder
        ? vscode.Uri.joinPath(workspaceFolder.uri, 'flamegraph.html')
        : vscode.Uri.file('flamegraph.html');

    const uri = await vscode.window.showSaveDialog({
        defaultUri,
        filters: { 'HTML': ['html'] },
        title: 'Save Flamegraph',
    });

    if (!uri) {
        return;
    }

    fs.writeFileSync(uri.fsPath, html, 'utf8');
    vscode.window.showInformationMessage(`Flamegraph saved to ${uri.fsPath}`);
}
