/**
 * Code Navigation — resolves Java fully-qualified class names to workspace
 * files and provides persistent line highlighting for JStall diagnostics.
 */

import * as vscode from 'vscode';

// ─── Severity types ───────────────────────────────────────────────

export type HighlightSeverity = 'error' | 'warning' | 'info';

// ─── Highlight state ──────────────────────────────────────────────

const severityDecorationTypes: Record<HighlightSeverity, vscode.TextEditorDecorationType> = {
    error: vscode.window.createTextEditorDecorationType({
        backgroundColor: 'rgba(255, 0, 0, 0.12)',
        border: '2px solid',
        borderColor: new vscode.ThemeColor('editorError.foreground'),
        isWholeLine: true,
        overviewRulerColor: new vscode.ThemeColor('editorOverviewRuler.errorForeground'),
        overviewRulerLane: vscode.OverviewRulerLane.Center,
    }),
    warning: vscode.window.createTextEditorDecorationType({
        backgroundColor: 'rgba(255, 200, 0, 0.12)',
        border: '2px solid',
        borderColor: new vscode.ThemeColor('editorWarning.foreground'),
        isWholeLine: true,
        overviewRulerColor: new vscode.ThemeColor('editorOverviewRuler.warningForeground'),
        overviewRulerLane: vscode.OverviewRulerLane.Center,
    }),
    info: vscode.window.createTextEditorDecorationType({
        backgroundColor: new vscode.ThemeColor('editor.findMatchHighlightBackground'),
        border: '2px solid',
        borderColor: new vscode.ThemeColor('editor.findMatchBorder'),
        isWholeLine: true,
        overviewRulerColor: new vscode.ThemeColor('editorOverviewRuler.findMatchForeground'),
        overviewRulerLane: vscode.OverviewRulerLane.Center,
    }),
};

/** Decoration type for inline explanation annotations. */
const explanationDecorationType = vscode.window.createTextEditorDecorationType({
    after: {
        color: new vscode.ThemeColor('editorCodeLens.foreground'),
        fontStyle: 'italic',
        margin: '0 0 0 2em',
    },
    isWholeLine: true,
});

/** Tracks highlighted ranges per file URI, grouped by severity. */
const highlightedRanges = new Map<string, Map<HighlightSeverity, vscode.Range[]>>();

/** Tracks explanation decorations per file URI. */
const explanationDecorations = new Map<string, vscode.DecorationOptions[]>();

// ─── File Resolution ──────────────────────────────────────────────

/**
 * Resolves a fully-qualified Java class name to a workspace file URI.
 * Handles inner classes (`$Inner` → stripped) and prefers `src/main/java/` matches.
 */
export async function resolveJavaClassToFile(fqcn: string): Promise<vscode.Uri | undefined> {
    // Strip inner class suffix (e.g. OrderService$Worker → OrderService)
    let className = fqcn;
    const dollarIdx = className.indexOf('$');
    if (dollarIdx !== -1) {
        className = className.substring(0, dollarIdx);
    }

    // Convert dots to path separators + .java extension
    const relativePath = className.replace(/\./g, '/') + '.java';
    const pattern = `**/${relativePath}`;

    const uris = await vscode.workspace.findFiles(pattern, '**/node_modules/**', 10);

    if (uris.length === 0) {
        return undefined;
    }
    if (uris.length === 1) {
        return uris[0];
    }

    // Prefer src/main/java over src/test/java or other locations
    const mainSource = uris.find(u => u.fsPath.includes('/src/main/java/'));
    if (mainSource) {
        return mainSource;
    }

    return uris[0];
}

// ─── Reveal & Highlight ──────────────────────────────────────────

export interface RevealOptions {
    /** Optional inline explanation shown after the highlighted line. */
    explanation?: string;
    /** Editor column to open in (for side-by-side view). */
    viewColumn?: vscode.ViewColumn;
    /** Highlight severity: 'error' (red), 'warning' (yellow), 'info' (blue, default). */
    severity?: HighlightSeverity;
    /** Optional end line (1-based) to highlight a range of lines instead of just one. */
    endLine?: number;
}

/**
 * Opens a file, moves cursor to the given line, and applies a persistent
 * highlight decoration. Multiple calls accumulate highlights.
 * Supports line ranges, severity-based colors, inline explanations, and view columns.
 */
export async function revealCodeLocation(uri: vscode.Uri, line: number, options?: RevealOptions): Promise<void> {
    const zeroLine = Math.max(0, line - 1); // convert 1-based to 0-based
    const severity: HighlightSeverity = options?.severity ?? 'info';
    const endZeroLine = options?.endLine ? Math.max(zeroLine, options.endLine - 1) : zeroLine;

    const editor = await vscode.window.showTextDocument(uri, {
        preview: false,
        preserveFocus: false,
        viewColumn: options?.viewColumn,
    });

    // Move cursor and reveal
    const position = new vscode.Position(zeroLine, 0);
    editor.selection = new vscode.Selection(position, position);
    editor.revealRange(
        new vscode.Range(position, position),
        vscode.TextEditorRevealType.InCenter,
    );

    // Accumulate ranges for this file+severity
    const key = uri.toString();
    if (!highlightedRanges.has(key)) {
        highlightedRanges.set(key, new Map());
    }
    const severityMap = highlightedRanges.get(key)!;
    const ranges = severityMap.get(severity) ?? [];

    // Build range (single line or multi-line)
    const lineRange = new vscode.Range(zeroLine, 0, endZeroLine, Number.MAX_SAFE_INTEGER);

    // Avoid duplicate highlights overlapping the same start line
    if (!ranges.some(r => r.start.line === zeroLine)) {
        ranges.push(lineRange);
        severityMap.set(severity, ranges);
    }

    // Apply decorations for all severities on this file
    for (const [sev, sevRanges] of severityMap) {
        editor.setDecorations(severityDecorationTypes[sev], sevRanges);
    }

    // Apply inline explanation annotation if provided (on the first line of the range)
    if (options?.explanation?.trim()) {
        const explanations = explanationDecorations.get(key) ?? [];
        // Avoid duplicate explanation on same line
        if (!explanations.some(d => d.range.start.line === zeroLine)) {
            explanations.push({
                range: new vscode.Range(zeroLine, 0, zeroLine, Number.MAX_SAFE_INTEGER),
                renderOptions: {
                    after: {
                        contentText: `  ← ${options.explanation.trim()}`,
                    },
                },
            });
            explanationDecorations.set(key, explanations);
        }
        editor.setDecorations(explanationDecorationType, explanations);
    }
}

/**
 * Clears all JStall highlights across all files.
 */
export function clearAllHighlights(): void {
    // Clear decorations on all visible editors that have highlights
    for (const editor of vscode.window.visibleTextEditors) {
        const key = editor.document.uri.toString();
        if (highlightedRanges.has(key)) {
            for (const sev of Object.keys(severityDecorationTypes) as HighlightSeverity[]) {
                editor.setDecorations(severityDecorationTypes[sev], []);
            }
        }
        if (explanationDecorations.has(key)) {
            editor.setDecorations(explanationDecorationType, []);
        }
    }
    highlightedRanges.clear();
    explanationDecorations.clear();
}

/**
 * Re-applies stored highlights when an editor becomes visible again.
 * Should be registered as a disposable in the extension activation.
 */
export function onDidChangeVisibleEditors(editors: readonly vscode.TextEditor[]): void {
    for (const editor of editors) {
        const key = editor.document.uri.toString();
        const severityMap = highlightedRanges.get(key);
        if (severityMap) {
            for (const [sev, ranges] of severityMap) {
                if (ranges.length > 0) {
                    editor.setDecorations(severityDecorationTypes[sev], ranges);
                }
            }
        }
        const explanations = explanationDecorations.get(key);
        if (explanations && explanations.length > 0) {
            editor.setDecorations(explanationDecorationType, explanations);
        }
    }
}
