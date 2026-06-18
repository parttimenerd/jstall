/**
 * Core JStall integration: JAR management, Java 17+ discovery, and running jstall commands.
 */

import * as vscode from 'vscode';
import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';
import { execFile, execFileSync, spawn } from 'child_process';

// --- Java 17+ discovery ---

let cachedJavaPath: string | undefined;

/**
 * Finds a Java 17+ installation on the system.
 * Searches: JAVA_HOME, macOS java_home utility, common JVM paths, PATH.
 */
export async function findJava17Plus(): Promise<string> {
    if (cachedJavaPath) {
        return cachedJavaPath;
    }

    // Check user-configured Java path first
    const configuredPath = vscode.workspace.getConfiguration('jstall').get<string>('javaPath');
    if (configuredPath) {
        try {
            const version = await getJavaVersion(configuredPath);
            if (version >= 17) {
                cachedJavaPath = configuredPath;
                return configuredPath;
            }
            console.warn(`JStall: Configured javaPath "${configuredPath}" is Java ${version}, need 17+.`);
        } catch {
            console.warn(`JStall: Configured javaPath "${configuredPath}" is not a valid Java installation.`);
        }
    }

    const candidates: string[] = [];

    // 1. JAVA_HOME
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
        const ext = process.platform === 'win32' ? '.exe' : '';
        candidates.push(path.join(javaHome, 'bin', `java${ext}`));
    }

    // 2. macOS: /usr/libexec/java_home -v 17+
    if (process.platform === 'darwin') {
        try {
            const jh = await execFileAsync('/usr/libexec/java_home', ['-v', '17+']);
            const trimmed = jh.trim();
            if (trimmed) {
                candidates.push(path.join(trimmed, 'bin', 'java'));
            }
        } catch {
            // java_home might not find any 17+ JVM
        }
    }

    // 3. Linux: common JVM directories
    if (process.platform === 'linux') {
        for (const dir of ['/usr/lib/jvm', '/usr/java', '/usr/local/java']) {
            if (fs.existsSync(dir)) {
                try {
                    for (const entry of fs.readdirSync(dir)) {
                        const javaPath = path.join(dir, entry, 'bin', 'java');
                        if (fs.existsSync(javaPath)) {
                            candidates.push(javaPath);
                        }
                    }
                } catch {
                    // permission issues
                }
            }
        }
    }

    // 4. Windows: common install locations
    if (process.platform === 'win32') {
        const programFiles = [
            process.env.ProgramFiles,
            process.env['ProgramFiles(x86)'],
            process.env.LOCALAPPDATA,
        ].filter(Boolean) as string[];

        const vendors = ['Java', 'Eclipse Adoptium', 'Amazon Corretto', 'Microsoft', 'Zulu', 'BellSoft'];
        for (const pf of programFiles) {
            for (const vendor of vendors) {
                const vendorDir = path.join(pf, vendor);
                if (fs.existsSync(vendorDir)) {
                    try {
                        for (const entry of fs.readdirSync(vendorDir)) {
                            const javaPath = path.join(vendorDir, entry, 'bin', 'java.exe');
                            if (fs.existsSync(javaPath)) {
                                candidates.push(javaPath);
                            }
                        }
                    } catch {}
                }
            }
        }
    }

    // 5. Fallback: java on PATH
    candidates.push('java');

    // Try each candidate, return first that is 17+
    for (const javaPath of candidates) {
        try {
            const version = await getJavaVersion(javaPath);
            if (version >= 17) {
                cachedJavaPath = javaPath;
                return javaPath;
            }
        } catch {
            // not found or not parseable
        }
    }

    throw new Error(
        'No Java 17+ found. Please install JDK 17 or later and set JAVA_HOME or add it to your PATH.'
    );
}

async function getJavaVersion(javaPath: string): Promise<number> {
    const output = await execFileAsync(javaPath, ['-version']);
    // Version string is in stderr: java version "17.0.1" or openjdk version "21.0.2"
    const match = /version "(\d+)/.exec(output);
    if (match) {
        return parseInt(match[1], 10);
    }
    throw new Error('Could not parse Java version');
}

function execFileAsync(cmd: string, args: string[]): Promise<string> {
    return new Promise<string>((resolve, reject) => {
        execFile(cmd, args, { timeout: 10000 }, (err, stdout, stderr) => {
            if (err && !stderr && !stdout) {
                reject(err as Error);
            } else {
                resolve(stdout + stderr);
            }
        });
    });
}

/** Reset cached Java path (useful for testing or after config change). */
export function resetJavaCache(): void {
    cachedJavaPath = undefined;
}

// --- JAR path ---

export function getJarPath(context: vscode.ExtensionContext): string {
    return path.join(context.extensionPath, 'lib', 'jstall.jar');
}

// --- Running jstall ---

export interface JStallResult {
    stdout: string;
    stderr: string;
    exitCode: number;
}

/**
 * Run a jstall command and capture output.
 */
export async function runJstall(
    context: vscode.ExtensionContext,
    args: string[],
    token?: vscode.CancellationToken
): Promise<JStallResult> {
    const javaPath = await findJava17Plus();
    const jarPath = getJarPath(context);

    if (!fs.existsSync(jarPath)) {
        throw new Error('jstall.jar not found in lib/. Run "npm run download-jar" to fetch it.');
    }

    return new Promise((resolve, reject) => {
        const proc = spawn(javaPath, ['-jar', jarPath, ...args]);

        const stdoutChunks: string[] = [];
        const stderrChunks: string[] = [];

        proc.stdout.on('data', (data: Buffer) => stdoutChunks.push(data.toString()));
        proc.stderr.on('data', (data: Buffer) => stderrChunks.push(data.toString()));

        let cancelSub: vscode.Disposable | undefined;
        if (token) {
            cancelSub = token.onCancellationRequested(() => proc.kill());
        }

        proc.on('close', (code) => {
            cancelSub?.dispose();
            resolve({
                stdout: stdoutChunks.join(''),
                stderr: stderrChunks.join(''),
                exitCode: code ?? 1,
            });
        });

        proc.on('error', (err) => {
            cancelSub?.dispose();
            reject(err);
        });
    });
}

/**
 * Run a jstall command and stream output to an OutputChannel.
 */
export async function runJstallToOutput(
    context: vscode.ExtensionContext,
    args: string[],
    output: vscode.OutputChannel,
    token?: vscode.CancellationToken
): Promise<number> {
    const javaPath = await findJava17Plus();
    const jarPath = getJarPath(context);

    if (!fs.existsSync(jarPath)) {
        throw new Error('jstall.jar not found in lib/.');
    }

    return new Promise((resolve, reject) => {
        const proc = spawn(javaPath, ['-jar', jarPath, ...args]);

        proc.stdout.on('data', (data: Buffer) => {
            output.append(stripAnsi(data.toString()));
        });
        proc.stderr.on('data', (data: Buffer) => {
            output.append(stripAnsi(data.toString()));
        });

        let cancelSub: vscode.Disposable | undefined;
        if (token) {
            cancelSub = token.onCancellationRequested(() => proc.kill());
        }

        proc.on('close', (code) => {
            cancelSub?.dispose();
            resolve(code ?? 1);
        });
        proc.on('error', (err) => {
            cancelSub?.dispose();
            reject(err);
        });
    });
}

// --- ANSI stripping ---

export function stripAnsi(text: string): string {
    // Strips all CSI sequences (colors, cursor movement, erase, scroll, etc.)
    return text.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '');
}

// --- Settings helpers ---

export function getStatusArgs(): string[] {
    const config = vscode.workspace.getConfiguration('jstall');
    const args: string[] = [];
    if (config.get<boolean>('fullDiagnostics')) { args.push('--full'); }
    if (config.get<boolean>('intelligentFilter')) { args.push('--intelligent-filter'); }
    if (config.get<boolean>('noNative')) { args.push('--no-native'); }
    if (config.get<boolean>('keep')) { args.push('--keep'); }
    const top = config.get<number>('top', 3);
    if (top !== 3) { args.push(`--top=${top}`); }
    return args;
}

export function getRecordArgs(): string[] {
    const config = vscode.workspace.getConfiguration('jstall');
    const args: string[] = [];
    const interval = config.get<number>('recordIntervalSeconds', 5);
    if (interval !== 5) { args.push(`--interval=${interval}s`); }
    if (config.get<boolean>('fullDiagnostics')) { args.push('--full'); }
    return args;
}

export function getFlameArgs(): string[] {
    const config = vscode.workspace.getConfiguration('jstall');
    const args: string[] = [];
    const duration = config.get<number>('flameDurationSeconds', 10);
    if (duration !== 10) { args.push(`--duration=${duration}s`); }
    return args;
}

// --- Recording path resolution ---

export interface RecordingSavePath {
    savePath: string;
    fileName: string;
}

// --- Error message extraction ---

/** Safely extract a human-readable message from an unknown thrown value. */
export function errorMessage(err: unknown): string {
    if (err instanceof Error) { return err.message; }
    return String(err);
}

/** Resolve the recording save path using the configured recording directory. */
export function resolveRecordingSavePath(target: number | string): RecordingSavePath {
    const wsFolder = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    const config = vscode.workspace.getConfiguration('jstall');
    const recordingDir = config.get<string>('recordingDir', '.jstall') || '.jstall';

    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').replace('T', '_').slice(0, 19);
    const fileName = `${target}-${timestamp}.zip`;

    let saveDir: string;
    if (wsFolder) {
        saveDir = path.isAbsolute(recordingDir)
            ? recordingDir
            : path.join(wsFolder, recordingDir);
    } else {
        saveDir = os.tmpdir();
    }

    if (!fs.existsSync(saveDir)) {
        fs.mkdirSync(saveDir, { recursive: true });
    }

    return { savePath: path.join(saveDir, fileName), fileName };
}

// --- Async-profiler native library extraction ---

/**
 * Extracts the async-profiler native library from jstall.jar to a temp directory.
 * Returns the path to the extracted .dylib/.so file.
 * Caches the extraction so it's only done once per session.
 */
let cachedProfilerLibPath: string | undefined;

export async function extractAsyncProfilerLib(context: vscode.ExtensionContext): Promise<string> {
    if (cachedProfilerLibPath && fs.existsSync(cachedProfilerLibPath)) {
        return cachedProfilerLibPath;
    }

    const jarPath = getJarPath(context);
    if (!fs.existsSync(jarPath)) {
        throw new Error('jstall.jar not found in lib/. Run "npm run download-jar" to fetch it.');
    }

    let libName: string;
    const platform = os.platform();
    const arch = os.arch();
    if (platform === 'darwin') {
        libName = 'libs/libasyncProfiler-4.4-macos.dylib';
    } else if (platform === 'linux' && arch === 'x64') {
        libName = 'libs/libasyncProfiler-4.4-linux-x64.so';
    } else if (platform === 'linux' && arch === 'arm64') {
        libName = 'libs/libasyncProfiler-4.4-linux-arm64.so';
    } else {
        throw new Error(`async-profiler is not supported on ${platform}/${arch}. Only macOS and Linux are supported.`);
    }

    const extractDir = path.join(os.tmpdir(), 'jstall-profiler');
    if (!fs.existsSync(extractDir)) {
        fs.mkdirSync(extractDir, { recursive: true });
    }

    const destPath = path.join(extractDir, path.basename(libName));

    // Extract using jar command or unzip
    if (!fs.existsSync(destPath)) {
        try {
            execFileSync('unzip', ['-o', '-j', jarPath, libName, '-d', extractDir], { timeout: 15000 });
        } catch {
            // Fallback: try jar command
            const javaPath = await findJava17Plus();
            const javaDir = path.dirname(javaPath);
            const jarCmd = path.join(javaDir, 'jar');
            execFileSync(jarCmd, ['xf', jarPath, libName], { cwd: extractDir, timeout: 15000 });
            // jar extracts with directory structure, move file up
            const extractedPath = path.join(extractDir, libName);
            if (fs.existsSync(extractedPath) && extractedPath !== destPath) {
                fs.renameSync(extractedPath, destPath);
            }
        }
    }

    if (!fs.existsSync(destPath)) {
        throw new Error(`Failed to extract async-profiler library from jstall.jar`);
    }

    cachedProfilerLibPath = destPath;
    return destPath;
}

// --- Run Java with async-profiler ---

export interface ProfiledRunResult {
    stdout: string;
    stderr: string;
    exitCode: number;
    profileOutputFile: string;
}

/**
 * Runs a Java application with async-profiler attached from the start.
 */
export async function runJavaProfiled(
    context: vscode.ExtensionContext,
    opts: {
        javaArgs: string[];
        event?: string;
        interval?: string;
        outputFile?: string;
        cwd?: string;
    },
    token?: vscode.CancellationToken
): Promise<ProfiledRunResult> {
    const javaPath = await findJava17Plus();
    const profilerLib = await extractAsyncProfilerLib(context);

    const event = opts.event || 'cpu';
    const outputFile = opts.outputFile || path.join(os.tmpdir(), `jstall-profile-${event}-${Date.now()}.collapsed`);

    // Build agentpath string
    let agentOpts = `start,event=${event},file=${outputFile}`;
    if (opts.interval) {
        agentOpts += `,interval=${opts.interval}`;
    }

    const fullArgs = [
        '--enable-native-access=ALL-UNNAMED',
        `-agentpath:${profilerLib}=${agentOpts}`,
        ...opts.javaArgs,
    ];

    const cwd = opts.cwd || vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;

    return new Promise((resolve, reject) => {
        const proc = spawn(javaPath, fullArgs, { cwd });

        const stdoutChunks: string[] = [];
        const stderrChunks: string[] = [];

        proc.stdout.on('data', (data: Buffer) => stdoutChunks.push(data.toString()));
        proc.stderr.on('data', (data: Buffer) => stderrChunks.push(data.toString()));

        let cancelSub: vscode.Disposable | undefined;
        if (token) {
            cancelSub = token.onCancellationRequested(() => proc.kill());
        }

        proc.on('close', (code) => {
            cancelSub?.dispose();
            resolve({
                stdout: stdoutChunks.join(''),
                stderr: stderrChunks.join(''),
                exitCode: code ?? 1,
                profileOutputFile: outputFile,
            });
        });

        proc.on('error', (err) => {
            cancelSub?.dispose();
            reject(err);
        });
    });
}
