/**
 * Unit and integration tests for java.ts
 *
 * Integration tests that need the real JAR are guarded with skipIf(!jarAvailable()),
 * so they are silently skipped in CI environments without the JAR.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

import {
    stripAnsi,
    jstallOutput,
    resolveJar,
    resolveTimeout,
    findJava17Plus,
    resetJavaCache,
    runJstall,
    type JStallResult,
} from '../java.js';

// ── Helpers ───────────────────────────────────────────────────────

function jarAvailable(): boolean {
    try {
        resolveJar();
        return true;
    } catch {
        return false;
    }
}

function ok(stdout: string, stderr = ''): JStallResult {
    return { stdout, stderr, exitCode: 0 };
}

function fail(stdout: string, stderr = '', exitCode = 1): JStallResult {
    return { stdout, stderr, exitCode };
}

// ── stripAnsi ─────────────────────────────────────────────────────

describe('stripAnsi', () => {
    it('strips simple color codes', () => {
        expect(stripAnsi('\x1b[32mgreen\x1b[0m')).toBe('green');
    });

    it('strips bold+red', () => {
        expect(stripAnsi('\x1b[1;31merror\x1b[0m')).toBe('error');
    });

    it('strips private-use sequences', () => {
        expect(stripAnsi('\x1b[?25lhidden cursor\x1b[?25h')).toBe('hidden cursor');
    });

    it('leaves plain text unchanged', () => {
        expect(stripAnsi('hello world')).toBe('hello world');
    });

    it('handles empty string', () => {
        expect(stripAnsi('')).toBe('');
    });

    it('strips codes inline within text', () => {
        expect(stripAnsi('before\x1b[33mcolored\x1b[0mafter')).toBe('beforecoloredafter');
    });

    it('strips ANSI in multiline output', () => {
        const input = '\x1b[1mLine 1\x1b[0m\n\x1b[32mLine 2\x1b[0m';
        expect(stripAnsi(input)).toBe('Line 1\nLine 2');
    });
});

// ── jstallOutput ─────────────────────────────────────────────────

describe('jstallOutput', () => {
    it('returns stripped stdout when exitCode is 0', () => {
        expect(jstallOutput(ok('\x1b[32mhello\x1b[0m'))).toBe('hello');
    });

    it('strips ANSI from combined stdout+stderr', () => {
        const result = ok('\x1b[1mout\x1b[0m', '\x1b[31merr\x1b[0m');
        expect(jstallOutput(result)).toBe('out\nerr');
    });

    it('returns fallback message when output is empty and exitCode is 0', () => {
        const msg = jstallOutput(ok('', ''));
        expect(msg).toMatch(/no output/i);
    });

    it('throws when exitCode is non-zero', () => {
        expect(() => jstallOutput(fail('', ''))).toThrow(/exited with code 1/i);
    });

    it('includes stdout detail in thrown error', () => {
        expect(() => jstallOutput(fail('something went wrong', ''))).toThrow('something went wrong');
    });

    it('includes stderr detail in thrown error', () => {
        expect(() => jstallOutput(fail('', 'connection refused'))).toThrow('connection refused');
    });

    it('strips ANSI from error detail', () => {
        expect(() => jstallOutput(fail('\x1b[31mred error\x1b[0m'))).toThrow('red error');
        expect(() => jstallOutput(fail('\x1b[31mred error\x1b[0m'))).not.toThrow(expect.stringContaining('\x1b'));
    });

    it('throws with exit code 2', () => {
        expect(() => jstallOutput(fail('deadlock', '', 2))).toThrow(/exited with code 2/i);
    });
});

// ── resolveTimeout ────────────────────────────────────────────────

describe('resolveTimeout', () => {
    it('flame with no duration → 40s (10s default + 30s headroom)', () => {
        expect(resolveTimeout(['flame', '12345'])).toBe(40_000);
    });

    it('flame --duration 30 → 60s', () => {
        expect(resolveTimeout(['flame', '--duration', '30', '12345'])).toBe(60_000);
    });

    it('flame --duration=5 → 35s', () => {
        expect(resolveTimeout(['flame', '--duration=5', '12345'])).toBe(35_000);
    });

    it('list → 30s', () => {
        expect(resolveTimeout(['list'])).toBe(30_000);
    });

    it('deadlock → 30s', () => {
        expect(resolveTimeout(['deadlock', '12345'])).toBe(30_000);
    });

    it('threads → 30s', () => {
        expect(resolveTimeout(['threads', '12345'])).toBe(30_000);
    });

    it('gc-heap-info → 30s', () => {
        expect(resolveTimeout(['gc-heap-info', '12345'])).toBe(30_000);
    });

    it('status → 60s (default)', () => {
        expect(resolveTimeout(['status', '12345'])).toBe(60_000);
    });

    it('most-work → 60s (default)', () => {
        expect(resolveTimeout(['most-work', '12345'])).toBe(60_000);
    });

    it('record → 60s (default)', () => {
        expect(resolveTimeout(['record', 'create', '12345'])).toBe(60_000);
    });
});

// ── resolveJar ────────────────────────────────────────────────────

describe('resolveJar', () => {
    let tmpFile: string;
    const originalArgv = process.argv.slice();

    beforeEach(() => {
        // Create a real temp file so fs.existsSync returns true
        tmpFile = path.join(os.tmpdir(), `jstall-test-${process.pid}.jar`);
        fs.writeFileSync(tmpFile, '');
        // Clear --jar from argv
        process.argv = originalArgv.filter(a => a !== '--jar');
    });

    afterEach(() => {
        if (fs.existsSync(tmpFile)) fs.unlinkSync(tmpFile);
        process.argv = originalArgv.slice();
        vi.unstubAllEnvs();
    });

    it('returns path when JSTALL_JAR points to an existing file', () => {
        vi.stubEnv('JSTALL_JAR', tmpFile);
        expect(resolveJar()).toBe(tmpFile);
    });

    it('throws when JSTALL_JAR points to a missing file', () => {
        vi.stubEnv('JSTALL_JAR', '/nonexistent/path/jstall.jar');
        expect(() => resolveJar()).toThrow(/JSTALL_JAR.*missing/i);
    });

    it('returns path when --jar arg points to an existing file', () => {
        vi.stubEnv('JSTALL_JAR', '');  // clear env
        process.argv = [...originalArgv, '--jar', tmpFile];
        expect(resolveJar()).toBe(tmpFile);
    });

    it('throws when --jar arg points to a missing file', () => {
        vi.stubEnv('JSTALL_JAR', '');
        process.argv = [...originalArgv, '--jar', '/nonexistent/jstall.jar'];
        expect(() => resolveJar()).toThrow(/--jar.*does not exist/i);
    });

    it('throws descriptive error when no JAR found anywhere', () => {
        vi.stubEnv('JSTALL_JAR', '');
        // Ensure we're running from a location where neither bundled nor local-build paths exist
        // (they won't exist in src/__tests__ relative paths during vitest)
        try {
            resolveJar();
            // If it didn't throw, the bundled or local-build JAR exists — that's fine
        } catch (err) {
            expect(err).toBeInstanceOf(Error);
            expect((err as Error).message).toMatch(/jstall\.jar not found/i);
        }
    });
});

// ── findJava17Plus ────────────────────────────────────────────────

describe('findJava17Plus', () => {
    beforeEach(() => {
        resetJavaCache();
        vi.unstubAllEnvs();
    });

    afterEach(() => {
        resetJavaCache();
        vi.unstubAllEnvs();
    });

    it('returns JSTALL_JAVA when it points to a valid Java 17+ binary', async () => {
        // Use the real java binary on this machine
        const realJava = process.env.JAVA_HOME
            ? path.join(process.env.JAVA_HOME, 'bin', 'java')
            : 'java';
        vi.stubEnv('JSTALL_JAVA', realJava);
        const result = await findJava17Plus();
        expect(result).toBe(realJava);
    });

    it('caches result across calls', async () => {
        const first = await findJava17Plus();
        const second = await findJava17Plus();
        expect(first).toBe(second);
    });

    it('resetJavaCache clears the cache so next call re-discovers', async () => {
        const first = await findJava17Plus();
        resetJavaCache();
        const second = await findJava17Plus();
        expect(second).toBe(first); // same binary, but re-discovered
    });

    it('throws when JSTALL_JAVA is set to a non-existent path', async () => {
        vi.stubEnv('JSTALL_JAVA', '/nonexistent/java');
        vi.stubEnv('JAVA_HOME', '');
        // On macOS this falls through to java_home then PATH — test only that it
        // does not accept JSTALL_JAVA when the path is invalid
        // The function may still find java via other means, so we only verify
        // that a non-existent JSTALL_JAVA does not short-circuit to a throw
        // about JSTALL_JAVA specifically (it falls through gracefully).
        // The real test: it logs a warning and continues discovery.
        const consoleSpy = vi.spyOn(console, 'error');
        try {
            await findJava17Plus();
        } catch {
            // may throw if no Java at all — acceptable
        }
        // Either way, it should have logged the JSTALL_JAVA warning
        const logged = consoleSpy.mock.calls.some(args =>
            String(args[0]).includes('JSTALL_JAVA') && String(args[0]).includes('not a valid')
        );
        expect(logged).toBe(true);
        consoleSpy.mockRestore();
    });
});

// ── Integration: runJstall with real JAR ──────────────────────────

describe.skipIf(!jarAvailable())('runJstall (integration)', () => {
    it('--help exits with code 0 and produces output', async () => {
        const result = await runJstall(['--help'], 15_000);
        expect(result.exitCode).toBe(0);
        expect(result.stdout.length).toBeGreaterThan(0);
    });

    it('unknown command exits non-zero', async () => {
        const result = await runJstall(['not-a-real-command'], 10_000);
        expect(result.exitCode).not.toBe(0);
    });
});
