#!/usr/bin/env python3
"""
Sync documentation script for jstall.

This script:
1. Builds the project and extracts CLI --help output for all commands
2. Updates README.md with current --help documentation

Usage:
    ./bin/sync-documentation.py           # Sync CLI help documentation
    ./bin/sync-documentation.py --dry-run # Preview changes without modifying files
    ./bin/sync-documentation.py --help    # Show help
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


def get_repo_root() -> Path:
    """Get the repository root directory (parent of bin/)."""
    return Path(__file__).parent.parent.resolve()


def read_file(path: Path) -> str:
    """Read file contents."""
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write_file(path: Path, content: str) -> None:
    """Write content to file."""
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)


def build_project_and_get_help(skip_build: bool = False) -> dict[str, str]:
    """Build project and extract --help output for each command."""
    repo_root = get_repo_root()

    if not skip_build:
        # Build the project
        print("Building project...")
        result = subprocess.run(
            ["mvn", "package", "-DskipTests", "-q"],
            cwd=repo_root,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            print(f"Warning: Maven build failed: {result.stderr}", file=sys.stderr)
            return {}

    jar_path = repo_root / "target/jstall.jar"
    if not jar_path.exists():
        print(f"Warning: JAR file not found at {jar_path}", file=sys.stderr)
        return {}

    help_outputs = {}

    root_result = subprocess.run(
        ["java", "-jar", str(jar_path), "--help"],
        cwd=repo_root,
        capture_output=True,
        text=True
    )
    if root_result.returncode == 0:
        help_outputs[""] = root_result.stdout.strip()
    elif root_result.stderr.strip():
        help_outputs[""] = root_result.stderr.strip()

    commands = [
        "list", "status", "deadlock", "most-work", "threads", "waiting-threads", "flame",
        "jvm-support", "dependency-graph", "dependency-tree", "processes",
        "gc-heap-info", "vm-vitals", "vm-metaspace", "vm-classloader-stats", "compiler-queue",
        "ai", "record",
    ]

    for cmd in commands:
        result = subprocess.run(
            ["java", "-jar", str(jar_path), cmd, "--help"],
            cwd=repo_root,
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            help_outputs[cmd] = result.stdout.strip()
        else:
            # Try stderr as some CLI frameworks output help there
            if result.stderr.strip():
                help_outputs[cmd] = result.stderr.strip()

    # Subcommands that need special invocation: "ai full"
    for parent, sub, marker in [("ai", "full", "ai full")]:
        result = subprocess.run(
            ["java", "-jar", str(jar_path), parent, sub, "--help"],
            cwd=repo_root,
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            help_outputs[marker] = result.stdout.strip()
        else:
            if result.stderr.strip():
                help_outputs[marker] = result.stderr.strip()

    return help_outputs


def update_readme_help_section(content: str, command: str, help_text: str) -> str:
    """
    Update a CLI help section in README.

    Sections are marked with either:
    <!-- BEGIN help -->
    ```
    help output
    ```
    <!-- END help -->

    or:
    <!-- BEGIN help_command -->
    ```
    help output
    ```
    <!-- END help_command -->
    """
    section_marker = "help" if command == "" else f"help_{command.replace('-', '_').replace(' ', '_')}"
    pattern = rf'(<!-- BEGIN {section_marker} -->\s*```(?:bash)?\s*)\s*(.*?)\s*(```\s*<!-- END {section_marker} -->)'

    if re.search(pattern, content, re.DOTALL):
        help_lines = help_text.splitlines()
        while help_lines and not help_lines[0].strip():
            help_lines.pop(0)
        while help_lines and not help_lines[-1].strip():
            help_lines.pop()

        cleaned_help = '\n'.join(help_lines)
        escaped_help = cleaned_help.replace('\\', '\\\\')
        return re.sub(
            pattern,
            rf'\g<1>{escaped_help}\n\g<3>',
            content,
            flags=re.DOTALL
        )

    return content


def sync_help_to_readme(readme_content: str, help_outputs: dict[str, str]) -> str:
    """Sync CLI --help outputs to README."""
    for command, help_text in help_outputs.items():
        readme_content = update_readme_help_section(readme_content, command, help_text)
        printable_command = "jstall" if command == "" else f"jstall {command}"
        print(f"Synced {printable_command} --help to README")

    return readme_content



def main():
    parser = argparse.ArgumentParser(
        description="Sync CLI help documentation for jstall",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  ./bin/sync-documentation.py              # Sync CLI help to README
  ./bin/sync-documentation.py --dry-run    # Preview changes
  ./bin/sync-documentation.py --skip-build # Use existing JAR without rebuilding
        """
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Preview changes without modifying files"
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Skip building project and use existing JAR"
    )

    args = parser.parse_args()

    repo_root = get_repo_root()
    readme_file = repo_root / "README.md"

    if not readme_file.exists():
        print(f"Error: README.md not found at {readme_file}", file=sys.stderr)
        sys.exit(1)

    # Read README
    readme_content = read_file(readme_file)
    original_content = readme_content

    # Build and sync CLI help (unless skipped)
    if args.skip_build:
        print("Skipping build (--skip-build specified)")
    help_outputs = build_project_and_get_help(skip_build=args.skip_build)
    if help_outputs:
        readme_content = sync_help_to_readme(readme_content, help_outputs)
    else:
        print("Warning: No help outputs extracted", file=sys.stderr)

    # Write updated README
    if readme_content != original_content:
        if args.dry_run:
            print("\n--- DRY RUN: Changes to README.md ---")
            # Show diff
            import difflib
            diff = difflib.unified_diff(
                original_content.splitlines(keepends=True),
                readme_content.splitlines(keepends=True),
                fromfile='README.md (original)',
                tofile='README.md (updated)'
            )
            print(''.join(diff))
            print("--- END DRY RUN ---")
        else:
            write_file(readme_file, readme_content)
            print("✓ Updated README.md")
    else:
        print("README.md is already up to date")

    print("Documentation sync complete!")


if __name__ == "__main__":
    main()