/**
 * Java 17+ discovery, JAR resolution, and jstall subprocess runner.
 * Ported from jstall-vscode-extension/src/jstall.ts with VSCode API removed.
 */

import * as os from 'os';
import * as path from 'path';
import * as fs from 'fs';
import { execFile, spawn } from 'child_process';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ── Java discovery ────────────────────────────────────────────────

let cachedJavaPath: string | undefined;

export async function findJava17Plus(): Promise<string> {
    if (cachedJavaPath) {
        return cachedJavaPath;
    }

    // Allow explicit override via env var
    const envJava = process.env.JSTALL_JAVA;
    if (envJava) {
        try {
            const version = await getJavaVersion(envJava);
            if (version >= 17) {
                cachedJavaPath = envJava;
                return envJava;
            }
            console.error(`JSTALL_JAVA="${envJava}" is Java ${version}, need 17+.`);
        } catch {
            console.error(`JSTALL_JAVA="${envJava}" is not a valid Java installation.`);
        }
    }

    const candidates: string[] = [];
    const ext = process.platform === 'win32' ? '.exe' : '';

    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
        candidates.push(path.join(javaHome, 'bin', `java${ext}`));
    }

    if (process.platform === 'darwin') {
        try {
            const jh = await execFileAsync('/usr/libexec/java_home', ['-v', '17+']);
            const trimmed = jh.trim();
            if (trimmed) {
                candidates.push(path.join(trimmed, 'bin', 'java'));
            }
        } catch {
            // no 17+ JVM found via java_home
        }
    }

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
                    // permission error
                }
            }
        }
    }

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
                    } catch {
                        // permission error
                    }
                }
            }
        }
    }

    candidates.push(`java${ext}`);

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
        'No Java 17+ found. Install JDK 17+ and set JAVA_HOME, or set JSTALL_JAVA to the java binary path.'
    );
}

async function getJavaVersion(javaPath: string): Promise<number> {
    const output = await execFileAsync(javaPath, ['-version']);
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
                reject(err);
            } else {
                resolve(stdout + stderr);
            }
        });
    });
}

export function resetJavaCache(): void {
    cachedJavaPath = undefined;
}

// ── JAR resolution ────────────────────────────────────────────────

export function resolveJar(): string {
    // Re-resolve every call so a stale cache doesn't point at a deleted file.
    // Resolution is cheap (just fs.existsSync), so no caching needed.

    // 1. --jar CLI argument
    const jarArgIdx = process.argv.indexOf('--jar');
    if (jarArgIdx !== -1 && process.argv[jarArgIdx + 1]) {
        const p = process.argv[jarArgIdx + 1];
        if (fs.existsSync(p)) return p;
        throw new Error(`--jar path does not exist: ${p}`);
    }

    // 2. JSTALL_JAR env var
    const envJar = process.env.JSTALL_JAR;
    if (envJar) {
        if (fs.existsSync(envJar)) return envJar;
        throw new Error(`JSTALL_JAR env var points to missing file: ${envJar}`);
    }

    // 3. Bundled in npm package: <server-dist-dir>/../lib/jstall.jar
    const bundled = path.join(__dirname, '..', 'lib', 'jstall.jar');
    if (fs.existsSync(bundled)) return bundled;

    // 4. Local build in parent jstall repo
    const localBuild = path.join(__dirname, '..', '..', 'target', 'jstall.jar');
    if (fs.existsSync(localBuild)) return localBuild;

    throw new Error(
        'jstall.jar not found. Options:\n' +
        '  1. Run: npm run download-jar\n' +
        '  2. Set JSTALL_JAR=/path/to/jstall.jar\n' +
        '  3. Pass --jar /path/to/jstall.jar'
    );
}

// ── Subprocess runner ─────────────────────────────────────────────

export interface JStallResult {
    stdout: string;
    stderr: string;
    exitCode: number;
}

const FLAME_DURATION_RE = /--duration[= ](\d+)/;

function resolveTimeout(args: string[]): number {
    // flame blocks for its profiling duration; add 30s headroom
    if (args[0] === 'flame') {
        const m = FLAME_DURATION_RE.exec(args.join(' '));
        const durationSec = m ? parseInt(m[1], 10) : 10;
        return (durationSec + 30) * 1000;
    }
    // fast read-only commands
    if (['list', 'deadlock', 'dependency-graph', 'dependency-tree',
         'threads', 'waiting-threads', 'gc-heap-info', 'vm-metaspace',
         'vm-classloader-stats', 'compiler-queue', 'vm-vitals',
         'jvm-support', 'processes'].includes(args[0])) {
        return 30_000;
    }
    return 60_000;
}

export async function runJstall(args: string[], timeoutMs?: number): Promise<JStallResult> {
    const javaPath = await findJava17Plus();
    const jarPath = resolveJar();
    const effectiveTimeout = timeoutMs ?? resolveTimeout(args);

    return new Promise((resolve, reject) => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), effectiveTimeout);

        const proc = spawn(javaPath, ['-jar', jarPath, ...args], {
            signal: controller.signal,
        });

        const stdoutChunks: string[] = [];
        const stderrChunks: string[] = [];

        proc.stdout.on('data', (data: Buffer) => stdoutChunks.push(data.toString()));
        proc.stderr.on('data', (data: Buffer) => stderrChunks.push(data.toString()));

        proc.on('close', (code) => {
            clearTimeout(timer);
            resolve({
                stdout: stdoutChunks.join(''),
                stderr: stderrChunks.join(''),
                exitCode: code ?? 1,
            });
        });

        proc.on('error', (err: NodeJS.ErrnoException) => {
            clearTimeout(timer);
            if (err.code === 'ABORT_ERR') {
                reject(new Error(`jstall timed out after ${effectiveTimeout / 1000}s`));
            } else {
                reject(err);
            }
        });
    });
}

// ── Output helpers ────────────────────────────────────────────────

export function stripAnsi(text: string): string {
    return text.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '');
}

export function jstallOutput(result: JStallResult): string {
    if (result.exitCode !== 0) {
        const detail = stripAnsi(result.stdout + (result.stderr ? '\n' + result.stderr : '')).trim();
        throw new Error(
            `jstall exited with code ${result.exitCode}` +
            (detail ? `:\n${detail}` : ' and produced no output.')
        );
    }
    const output = stripAnsi(
        result.stdout + (result.stderr ? '\n' + result.stderr : '')
    ).trim();
    return output || `Command exited with code ${result.exitCode} and produced no output.`;
}
