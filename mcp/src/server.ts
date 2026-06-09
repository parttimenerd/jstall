#!/usr/bin/env node
/**
 * jstall MCP server — wraps the jstall CLI as three tools:
 *   jstall_help   — show help / command list
 *   jstall_run    — run any jstall command locally
 *   jstall_remote — run any jstall command via SSH or Cloud Foundry
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
    ListToolsRequestSchema,
    CallToolRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { runJstall, jstallOutput, stripAnsi } from './java.js';

// Safe SSH target: user@host, host, user@host:port, IPv4, hostnames with dots/dashes
export const SAFE_SSH_TARGET_RE = /^[a-zA-Z0-9][a-zA-Z0-9._@:\-]*$/;

// ── Command autodiscovery ─────────────────────────────────────────

export interface CommandEntry {
    name: string;
    description: string;
}

export function parseCommands(helpText: string): CommandEntry[] {
    const commands: CommandEntry[] = [];
    const lines = helpText.split('\n');
    let inCommands = false;
    for (const line of lines) {
        if (/^Commands:/.test(line)) { inCommands = true; continue; }
        if (!inCommands) continue;
        // A command line is indented 2 spaces, then a name, then spaces, then description
        const m = /^  ([a-z][a-z0-9-]*)[ \t]+(.+)$/.exec(line);
        if (m) {
            commands.push({ name: m[1], description: m[2].trim() });
        } else if (line.trim() === '' || /^\S/.test(line)) {
            break; // end of commands block
        }
    }
    return commands;
}

async function discoverCommands(): Promise<CommandEntry[]> {
    try {
        const result = await runJstall(['--help'], 15_000);
        return parseCommands(stripAnsi(result.stdout + result.stderr));
    } catch {
        return [];
    }
}

function commandListText(commands: CommandEntry[]): string {
    if (commands.length === 0) return '';
    return '\n\nAvailable commands:\n' +
        commands.map(c => `  ${c.name.padEnd(22)} ${c.description}`).join('\n');
}

// ── Server setup ──────────────────────────────────────────────────

const server = new Server(
    { name: 'jstall', version: '0.7.1' },
    { capabilities: { tools: {} } },
);

// ── Tool definitions ──────────────────────────────────────────────

export function buildTools(commands: CommandEntry[]) {
    const cmdList = commandListText(commands);
    return [
        {
            name: 'jstall_help',
            description:
                'Show help for jstall commands. ' +
                'Call with no arguments for the full command list, or pass a command name ' +
                'to get its flags and usage, e.g. command="status", command="flame", ' +
                'command="record create" (subcommands use a space, e.g. "record create").',
            inputSchema: {
                type: 'object',
                properties: {
                    command: {
                        type: 'string',
                        description: 'Optional command name, e.g. "status", "flame", "record create", "record summary".',
                    },
                },
            },
        },
        {
            name: 'jstall_run',
            description:
                'Run any jstall command on a local JVM. ' +
                'Call jstall_help first if unsure which command or flags to use.' +
                cmdList,
            inputSchema: {
                type: 'object',
                properties: {
                    args: {
                        type: 'array',
                        items: { type: 'string' },
                        description: 'jstall arguments, e.g. ["status", "--intelligent-filter", "--no-native", "12345"]',
                    },
                },
                required: ['args'],
            },
        },
        {
            name: 'jstall_remote',
            description:
                'Run any jstall command on a remote JVM via SSH or Cloud Foundry. ' +
                'Use args=["list"] first to discover PIDs on the remote host, then diagnose with ' +
                'args=["status", "--intelligent-filter", "--no-native", "<pid>"] etc. ' +
                'jstall must be available on the remote host (in PATH or via jbang).' +
                cmdList,
            inputSchema: {
                type: 'object',
                properties: {
                    type: {
                        type: 'string',
                        enum: ['ssh', 'cf'],
                        description: '"ssh" for SSH, "cf" for Cloud Foundry.',
                    },
                    target: {
                        type: 'string',
                        description: 'SSH: "user@hostname". CF: application name.',
                    },
                    args: {
                        type: 'array',
                        items: { type: 'string' },
                        description: 'jstall arguments, same as jstall_run.',
                    },
                },
                required: ['type', 'target', 'args'],
            },
        },
    ] as const;
}

// Discover commands at startup; fall back to an empty list if jstall is unavailable.
const commands = await discoverCommands();
export const TOOLS = buildTools(commands);

// ── Request handlers ──────────────────────────────────────────────

export function buildHelpArgs(command?: string): string[] {
    const cmd = command?.trim();
    return cmd ? [...cmd.split(/\s+/), '--help'] : ['--help'];
}

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
    const { name, arguments: args = {} } = req.params;

    try {
        let text: string;

        if (name === 'jstall_help') {
            const helpArgs = buildHelpArgs(args.command as string | undefined);
            text = jstallOutput(await runJstall(helpArgs, 15_000));

        } else if (name === 'jstall_run') {
            const cmdArgs = args.args as string[];
            if (!Array.isArray(cmdArgs) || cmdArgs.length === 0) {
                throw new Error('args must be a non-empty array. Try ["--help"].');
            }
            if (cmdArgs.some(a => typeof a !== 'string')) {
                throw new Error('All args elements must be strings.');
            }
            text = jstallOutput(await runJstall(cmdArgs));

        } else if (name === 'jstall_remote') {
            const type = args.type as 'ssh' | 'cf';
            const target = (args.target as string)?.trim();
            const cmdArgs = args.args as string[];

            if (type !== 'ssh' && type !== 'cf') {
                throw new Error('"type" must be "ssh" or "cf".');
            }
            if (!target) throw new Error('"target" is required.');
            if (type === 'ssh' && !SAFE_SSH_TARGET_RE.test(target)) {
                throw new Error('"target" contains invalid characters. Expected user@hostname or hostname.');
            }
            if (!Array.isArray(cmdArgs) || cmdArgs.length === 0) {
                throw new Error('"args" must be a non-empty array.');
            }
            if (cmdArgs.some(a => typeof a !== 'string')) {
                throw new Error('All args elements must be strings.');
            }

            const flag = type === 'ssh' ? '--ssh' : '--cf';
            const flagValue = type === 'ssh' ? `ssh ${target}` : target;
            text = jstallOutput(await runJstall([flag, flagValue, ...cmdArgs]));

        } else {
            return { content: [{ type: 'text', text: `Unknown tool: ${name}` }], isError: true };
        }

        return { content: [{ type: 'text', text }] };
    } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        return { content: [{ type: 'text', text: `Error: ${msg}` }], isError: true };
    }
});

// ── Start ─────────────────────────────────────────────────────────

const transport = new StdioServerTransport();
await server.connect(transport);
