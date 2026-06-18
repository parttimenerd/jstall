# Change Log

All notable changes to the JStall VS Code extension will be documented in this file.

Follows [Keep a Changelog](http://keepachangelog.com/).

## [Unreleased]

## [0.1.1]

### Added
- Remote JVM diagnostics: new `JStall: Remote` command to connect to JVMs via SSH or Cloud Foundry and run any supported jstall command (status, flamegraph, record, threads, deadlock, etc.)
- Remote target picker with saved connections and last-used memory
- `jstall.remote.sshHosts` and `jstall.remote.cfApps` settings for pre-configured remote targets
- `jstall_remote` Language Model Tool for AI assistants (Copilot) to run remote JVM diagnostics

### Changed
- Use new jstall version

## [0.1.0]

### Added
- Initial implementation