#!/usr/bin/env node
/**
 * jstall MCP server — wraps the jstall CLI as two tools:
 *   jstall_run    — run any jstall command locally
 *   jstall_remote — run any jstall command via SSH or Cloud Foundry
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
    ListToolsRequestSchema,
    CallToolRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { runJstall, jstallOutput } from './java.js';

// Safe SSH target: user@host, host, user@host:port, IPv4, hostnames with dots/dashes
const SAFE_SSH_TARGET_RE = /^[a-zA-Z0-9][a-zA-Z0-9._@:\-]*$/;

const server = new Server(
    { name: 'jstall', version: '0.7.1' },
    { capabilities: { tools: {} } },
);

// ── Tool definitions ──────────────────────────────────────────────

const TOOLS = [
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
            'Call jstall_help first if unsure which command or flags to use.',
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
            'Use command=["list"] first to discover PIDs on the remote host, then diagnose with ' +
            'command=["status", "--intelligent-filter", "--no-native", "<pid>"] etc. ' +
            'jstall must be available on the remote host (in PATH or via jbang).',
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

// ── Request handlers ──────────────────────────────────────────────

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
    const { name, arguments: args = {} } = req.params;

    try {
        let text: string;

        if (name === 'jstall_help') {
            const cmd = (args.command as string | undefined)?.trim();
            // split "record create" → ["record", "create", "--help"]
            const helpArgs = cmd ? [...cmd.split(/\s+/), '--help'] : ['--help'];
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
