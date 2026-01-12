# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.4.3] - 2026-01-12

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.4.2] - 2026-01-12

### Added
- `waiting-threads` command to identify threads waiting on the same lock instance across all thread dumps with no CPU time progress
- Better name discovery for JVMs without a JMX label (using the process command as a fallback)

### Changed
- List output to make it more concise
- Improved README

### Removed
- GraalVM native image support due to complexity and maintenance overhead

## [0.4.1] - 2026-01-04

### Added
- GraalVM native image support

### Changed
- Improved performance by using the JMX API instead of calling external commands

## [0.4.0] - 2026-01-03

### Added
- `list` command
- Allow specifying JVM via a label filter

## [0.3.4] - 2025-12-30

- The releaser script doesn't like force pushes

## [0.3.3] - 2025-12-30

### Fixed
- Fixed `flame` command
- Removed `at at` from stack traces, making them easier to read

## [0.3.2] - 2025-12-30

### Fixed
- Fixed main help message

## [0.3.1] - 2025-12-30

### Changed
- Improve main help message

## [0.3.0] - 2025-12-30

### Fixed
- Fixed the `dead-lock` command and renamed it to `deadlock`

## [0.2.0] - 2025-12-29

### Changed
- use jstack for thread dump capture instead of jcmd
- remove `--json` option, as this lead to a lot of duplicated code
- add `threads` command

## [0.1.0] - 2025-12-29

### Added
- Initial implementation