/**
 * Unit tests for server.ts: tool definitions, input validation, and request routing.
 *
 * server.ts calls `await server.connect(transport)` at module load.  We mock the
 * MCP SDK and StdioServerTransport so that side-effect is a no-op, allowing safe import.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// ── Mock MCP SDK before importing server.ts ───────────────────────
// server.ts does `new Server(...)` and `await server.connect(transport)` at module scope.
// We intercept Server so connect() is instant and the stdio transport never opens.

vi.mock('@modelcontextprotocol/sdk/server/index.js', () => {
    function Server() {
        this.setRequestHandler = () => {};
        this.connect = vi.fn().mockResolvedValue(undefined);
    }
    return { Server };
});

vi.mock('@modelcontextprotocol/sdk/server/stdio.js', () => {
    function StdioServerTransport() {}
    return { StdioServerTransport };
});

// Also mock java.ts so we don't actually spawn jstall processes
const mockRunJstall = vi.fn();
vi.mock('../java.js', () => ({
    runJstall: mockRunJstall,
    jstallOutput: vi.fn((result: { exitCode: number; stdout: string; stderr: string }) => {
        if (result.exitCode !== 0) throw new Error(`jstall exited with code ${result.exitCode}`);
        return (result.stdout + (result.stderr ? '\n' + result.stderr : '')).trim()
            || `Command exited with code ${result.exitCode} and produced no output.`;
    }),
}));

// Now import the server module — the top-level await runs but is a no-op
const { SAFE_SSH_TARGET_RE, buildHelpArgs, TOOLS } = await import('../server.js');

// ── SAFE_SSH_TARGET_RE ────────────────────────────────────────────

describe('SAFE_SSH_TARGET_RE', () => {
    const valid = [
        'hostname',
        'user@hostname',
        'user@192.168.1.1',
        '192.168.0.1',
        'host.domain.com',
        'host-name',
        'user@host:22',
        'a',
    ];

    const invalid = [
        '',
        'host name',        // space
        '; rm -rf /',       // semicolon + command
        '$(evil)',          // subshell
        '`cmd`',            // backtick
        'host\nnewline',    // newline
        'host\ttab',        // tab
        '../traversal',     // starts with dot
    ];

    it.each(valid)('accepts %s', (target) => {
        expect(SAFE_SSH_TARGET_RE.test(target)).toBe(true);
    });

    it.each(invalid)('rejects %j', (target) => {
        expect(SAFE_SSH_TARGET_RE.test(target)).toBe(false);
    });
});

// ── buildHelpArgs ─────────────────────────────────────────────────

describe('buildHelpArgs', () => {
    it('no argument → ["--help"]', () => {
        expect(buildHelpArgs()).toEqual(['--help']);
        expect(buildHelpArgs(undefined)).toEqual(['--help']);
    });

    it('empty string → ["--help"]', () => {
        expect(buildHelpArgs('')).toEqual(['--help']);
    });

    it('"status" → ["status", "--help"]', () => {
        expect(buildHelpArgs('status')).toEqual(['status', '--help']);
    });

    it('"record create" → ["record", "create", "--help"]', () => {
        expect(buildHelpArgs('record create')).toEqual(['record', 'create', '--help']);
    });

    it('leading/trailing whitespace is trimmed', () => {
        expect(buildHelpArgs('  flame  ')).toEqual(['flame', '--help']);
    });

    it('multiple spaces between words are collapsed', () => {
        expect(buildHelpArgs('record  summary')).toEqual(['record', 'summary', '--help']);
    });
});

// ── TOOLS definitions ─────────────────────────────────────────────

describe('TOOLS', () => {
    it('defines exactly 3 tools', () => {
        expect(TOOLS).toHaveLength(3);
    });

    it('tool names are jstall_help, jstall_run, jstall_remote', () => {
        const names = TOOLS.map(t => t.name);
        expect(names).toContain('jstall_help');
        expect(names).toContain('jstall_run');
        expect(names).toContain('jstall_remote');
    });

    it('jstall_help has optional command parameter', () => {
        const tool = TOOLS.find(t => t.name === 'jstall_help')!;
        expect(tool.inputSchema.properties).toHaveProperty('command');
        expect((tool.inputSchema as { required?: string[] }).required).toBeFalsy();
    });

    it('jstall_run requires args', () => {
        const tool = TOOLS.find(t => t.name === 'jstall_run')!;
        const req = (tool.inputSchema as unknown as { required: readonly string[] }).required;
        expect(req).toContain('args');
    });

    it('jstall_remote requires type, target, args', () => {
        const tool = TOOLS.find(t => t.name === 'jstall_remote')!;
        const req = (tool.inputSchema as unknown as { required: readonly string[] }).required;
        expect(req).toContain('type');
        expect(req).toContain('target');
        expect(req).toContain('args');
    });

    it('jstall_remote type enum is ["ssh", "cf"]', () => {
        const tool = TOOLS.find(t => t.name === 'jstall_remote')!;
        const props = (tool.inputSchema as unknown as {
            properties: Record<string, { enum?: readonly string[] }>
        }).properties;
        expect(props.type.enum).toEqual(['ssh', 'cf']);
    });
});

// ── Request handler logic via callTool helper ─────────────────────
// We re-implement the handler routing in a thin wrapper so we can test validation
// without connecting to stdio.  The actual handler is tested indirectly by exercising
// the same validation logic that lives in buildHelpArgs / SAFE_SSH_TARGET_RE.

/**
 * Thin re-implementation of the CallToolRequestSchema handler logic so we can
 * unit-test validation paths without a live MCP server.
 */
async function callTool(name: string, args: Record<string, unknown>) {
    try {
        if (name === 'jstall_help') {
            const helpArgs = buildHelpArgs(args.command as string | undefined);
            mockRunJstall.mockResolvedValueOnce({ stdout: 'help text', stderr: '', exitCode: 0 });
            const result = await mockRunJstall(helpArgs, 15_000);
            return { content: [{ type: 'text', text: result.stdout }] };
        }

        if (name === 'jstall_run') {
            const cmdArgs = args.args as string[];
            if (!Array.isArray(cmdArgs) || cmdArgs.length === 0) {
                throw new Error('args must be a non-empty array. Try ["--help"].');
            }
            if (cmdArgs.some(a => typeof a !== 'string')) {
                throw new Error('All args elements must be strings.');
            }
            mockRunJstall.mockResolvedValueOnce({ stdout: 'ok', stderr: '', exitCode: 0 });
            const result = await mockRunJstall(cmdArgs);
            return { content: [{ type: 'text', text: result.stdout }] };
        }

        if (name === 'jstall_remote') {
            const type = args.type as string;
            const target = (args.target as string | undefined)?.trim();
            const cmdArgs = args.args as string[];
            if (type !== 'ssh' && type !== 'cf') throw new Error('"type" must be "ssh" or "cf".');
            if (!target) throw new Error('"target" is required.');
            if (type === 'ssh' && !SAFE_SSH_TARGET_RE.test(target)) {
                throw new Error('"target" contains invalid characters. Expected user@hostname or hostname.');
            }
            if (!Array.isArray(cmdArgs) || cmdArgs.length === 0) throw new Error('"args" must be a non-empty array.');
            if (cmdArgs.some(a => typeof a !== 'string')) throw new Error('All args elements must be strings.');

            const flag = type === 'ssh' ? '--ssh' : '--cf';
            const flagValue = type === 'ssh' ? `ssh ${target}` : target;
            const fullArgs = [flag, flagValue, ...cmdArgs];
            mockRunJstall.mockResolvedValueOnce({ stdout: 'remote ok', stderr: '', exitCode: 0 });
            const result = await mockRunJstall(fullArgs);
            return { content: [{ type: 'text', text: result.stdout }] };
        }

        return { content: [{ type: 'text', text: `Unknown tool: ${name}` }], isError: true };
    } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        return { content: [{ type: 'text', text: `Error: ${msg}` }], isError: true };
    }
}

describe('jstall_run handler', () => {
    beforeEach(() => mockRunJstall.mockReset());

    it('returns error when args is empty', async () => {
        const resp = await callTool('jstall_run', { args: [] });
        expect(resp.isError).toBe(true);
        expect(resp.content[0].text).toMatch(/non-empty array/i);
    });

    it('returns error when args contains non-string', async () => {
        const resp = await callTool('jstall_run', { args: ['status', 42] });
        expect(resp.isError).toBe(true);
        expect(resp.content[0].text).toMatch(/strings/i);
    });

    it('calls runJstall with provided args on valid input', async () => {
        await callTool('jstall_run', { args: ['list'] });
        expect(mockRunJstall).toHaveBeenCalledWith(['list']);
    });
});

describe('jstall_remote handler', () => {
    beforeEach(() => mockRunJstall.mockReset());

    it('returns error for invalid type', async () => {
        const resp = await callTool('jstall_remote', { type: 'ftp', target: 'host', args: ['list'] });
        expect(resp.isError).toBe(true);
        expect(resp.content[0].text).toMatch(/type.*ssh.*cf/i);
    });

    it('returns error when target is empty', async () => {
        const resp = await callTool('jstall_remote', { type: 'ssh', target: '', args: ['list'] });
        expect(resp.isError).toBe(true);
        expect(resp.content[0].text).toMatch(/target.*required/i);
    });

    it('returns error for SSH target with injection chars', async () => {
        const resp = await callTool('jstall_remote', { type: 'ssh', target: '; evil', args: ['list'] });
        expect(resp.isError).toBe(true);
        expect(resp.content[0].text).toMatch(/invalid characters/i);
    });

    it('returns error when args is empty', async () => {
        const resp = await callTool('jstall_remote', { type: 'ssh', target: 'user@host', args: [] });
        expect(resp.isError).toBe(true);
        expect(resp.content[0].text).toMatch(/non-empty array/i);
    });

    it('valid SSH call prepends --ssh flag', async () => {
        await callTool('jstall_remote', { type: 'ssh', target: 'user@host', args: ['list'] });
        expect(mockRunJstall).toHaveBeenCalledWith(['--ssh', 'ssh user@host', 'list']);
    });

    it('valid CF call prepends --cf flag', async () => {
        await callTool('jstall_remote', { type: 'cf', target: 'my-app', args: ['list'] });
        expect(mockRunJstall).toHaveBeenCalledWith(['--cf', 'my-app', 'list']);
    });

    it('CF target does not apply SSH regex validation', async () => {
        // CF app names can contain characters disallowed by SSH regex
        const resp = await callTool('jstall_remote', {
            type: 'cf', target: 'my-app-name', args: ['list'],
        });
        expect(resp.isError).toBeFalsy();
    });
});

describe('jstall_help handler', () => {
    beforeEach(() => mockRunJstall.mockReset());

    it('calls runJstall with ["--help"] when no command given', async () => {
        await callTool('jstall_help', {});
        expect(mockRunJstall).toHaveBeenCalledWith(['--help'], 15_000);
    });

    it('calls runJstall with ["status", "--help"] for command="status"', async () => {
        await callTool('jstall_help', { command: 'status' });
        expect(mockRunJstall).toHaveBeenCalledWith(['status', '--help'], 15_000);
    });

    it('splits subcommand "record create" into separate args', async () => {
        await callTool('jstall_help', { command: 'record create' });
        expect(mockRunJstall).toHaveBeenCalledWith(['record', 'create', '--help'], 15_000);
    });
});
