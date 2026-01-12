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


def build_project_and_get_help() -> dict[str, str]:
    """Build project and extract --help output for each command."""
    repo_root = get_repo_root()

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
    commands = ["list", "status", "deadlock", "most-work", "threads", "waiting-threads", "flame"]

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

    return help_outputs


def update_readme_help_section(content: str, command: str, help_text: str) -> str:
    """
    Update a CLI help section in README.

    Sections are marked with:
    <!-- BEGIN help_command -->
    ```
    help output
    ```
    <!-- END help_command -->
    """
    # Convert "most-work" to "most_work"
    section_marker = f"help_{command.replace('-', '_')}"
    # Match the markers, capturing the opening and closing without their internal whitespace
    pattern = rf'(<!-- BEGIN {section_marker} -->\s*```)\s*(.*?)\s*(```\s*<!-- END {section_marker} -->)'

    if re.search(pattern, content, re.DOTALL):
        # Strip leading and trailing empty lines from help text
        help_lines = help_text.splitlines()
        # Remove leading empty lines
        while help_lines and not help_lines[0].strip():
            help_lines.pop(0)
        # Remove trailing empty lines
        while help_lines and not help_lines[-1].strip():
            help_lines.pop()

        cleaned_help = '\n'.join(help_lines)

        # Escape backslashes in help_text to prevent regex interpretation
        escaped_help = cleaned_help.replace('\\', '\\\\')
        # Put content immediately after opening ``` with a newline, then content, then newline before closing ```
        return re.sub(
            pattern,
            rf'\g<1>\n{escaped_help}\n\g<3>',
            content,
            flags=re.DOTALL
        )

    return content



def sync_help_to_readme(readme_content: str, help_outputs: dict[str, str]) -> str:
    """Sync CLI --help outputs to README."""
    for command, help_text in help_outputs.items():
        readme_content = update_readme_help_section(readme_content, command, help_text)
        print(f"Synced {command} --help to README")

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
    if not args.skip_build:
        help_outputs = build_project_and_get_help()
        if help_outputs:
            readme_content = sync_help_to_readme(readme_content, help_outputs)
        else:
            print("Warning: No help outputs extracted", file=sys.stderr)
    else:
        print("Skipping build (--skip-build specified)")

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