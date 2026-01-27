#!/usr/bin/env python3
"""release.py

Default command: bump version and release.

Additional command:
  build-minimal: create a minimal variant (jstall-minimal) in a temporary working directory.
"""

import re
import sys
import subprocess
import argparse
from pathlib import Path
from typing import Tuple


def _copytree(src: Path, dst: Path):
    """Copy repository contents to dst, excluding build/output folders."""
    import shutil

    ignore_names = {
        '.git',
        'target',
        '.release-backup',
        'dumps',
        'harness-215430-12701372015461000502',
        'harness-221534-4014366627897400427',
    }

    def _ignore(_dir, names):
        return {n for n in names if n in ignore_names}

    shutil.copytree(src, dst, ignore=_ignore)


def _patch_pom_for_minimal(pom_path: Path):
    """Patch pom.xml for minimal build."""
    content = pom_path.read_text()

    # 1) rename artifactId (project)
    # Replace only the first <artifactId>...</artifactId> after groupId
    content = re.sub(r'(<groupId>me\\.bechberger</groupId>\s*\n\s*<artifactId>)([^<]+)(</artifactId>)',
                     r'\1jstall-minimal\3', content, count=1)

    # 2) dependency: jthreaddump -> jthreaddump-minimal
    content = content.replace('<artifactId>jthreaddump</artifactId>', '<artifactId>jthreaddump-minimal</artifactId>')

    # 3) dependency: ap-loader-all -> ap-loader-none
    content = content.replace('<artifactId>ap-loader-all</artifactId>', '<artifactId>ap-loader-none</artifactId>')

    # 4) remove JetBrains annotations dependency block
    # (pom uses groupId "org.jetbrains" and artifactId "annotations")
    content = re.sub(
        r'\s*<dependency>\s*(?:(?!</dependency>).)*?<groupId>\s*org\.jetbrains\s*</groupId>'
        r'(?:(?!</dependency>).)*?<artifactId>\s*annotations\s*</artifactId>'
        r'(?:(?!</dependency>).)*?</dependency>\s*',
        '\n',
        content,
        flags=re.DOTALL
    )

    # Use the custom minimal assembly descriptor for the fat jar
    # Replace descriptorRefs/descriptorRef with a descriptor
    content = re.sub(
        r'(<plugin>\s*(?:(?!</plugin>).)*?<artifactId>maven-assembly-plugin</artifactId>\s*(?:(?!</plugin>).)*?<configuration>)\s*<descriptorRefs>\s*<descriptorRef>jar-with-dependencies</descriptorRef>\s*</descriptorRefs>\s*',
        r'\1\n                    <descriptors>\n                        <descriptor>src/assembly/minimal.xml</descriptor>\n                    </descriptors>\n',
        content,
        flags=re.DOTALL,
    )

    # 5) Remove --add-reads javadoc options (not needed for minimal and causes issues)
    # Matches the two lines: <additionalJOption>--add-reads</additionalJOption> and the following value line
    content = re.sub(
        r'\s*<additionalJOption>--add-reads</additionalJOption>\s*\n\s*<additionalJOption>[^<]*</additionalJOption>',
        '',
        content
    )

    # 6) Minimal builds: strip debug symbols (-g:none)
    # Add compilerArgs to the existing maven-compiler-plugin configuration (keep <release>21</release> intact).
    if '-g:none' not in content:
        content = re.sub(
            r'(<plugin>\s*(?:(?!</plugin>).)*?<artifactId>maven-compiler-plugin</artifactId>\s*'
            r'(?:(?!</plugin>).)*?<configuration>)(\s*(?:(?!</configuration>).)*)</configuration>',
            lambda m: (
                m.group(1)
                + m.group(2)
                + ('\n' if not m.group(2).endswith('\n') else '')
                + '                    <compilerArgs>\n'
                + '                        <arg>-g:none</arg>\n'
                + '                    </compilerArgs>\n'
                + '                </configuration>'
            ),
            content,
            flags=re.DOTALL,
            count=1,
        )

    pom_path.write_text(content)


def _strip_nullable_annotations(root: Path):
    """Remove org.jetbrains.annotations.{Nullable,NotNull} imports + all @Nullable/@NotNull annotations.

    Minimal builds remove the JetBrains annotations dependency from the POM, so any remaining
    usages would break compilation.
    """
    java_files = list(root.glob('src/**/*.java'))
    for file in java_files:
        text = file.read_text()
        new_text = text

        # Remove JetBrains Nullable/NotNull import
        new_text = re.sub(r'^\s*import\s+org\.jetbrains\.annotations\.Nullable;\s*\n', '', new_text, flags=re.MULTILINE)
        new_text = re.sub(r'^\s*import\s+org\.jetbrains\.annotations\.NotNull;\s*\n', '', new_text, flags=re.MULTILINE)

        # Remove any local Nullable import used only for annotation
        # (in this repo it appears as me.bechberger.jstall.util.Nullable)
        new_text = re.sub(r'^\s*import\s+me\.bechberger\.jstall\.util\.Nullable;\s*\n', '', new_text, flags=re.MULTILINE)

        # Remove annotation occurrences (simple token delete)
        new_text = new_text.replace('@Nullable ', '')
        new_text = new_text.replace('@Nullable', '')
        new_text = new_text.replace('@NotNull ', '')
        new_text = new_text.replace('@NotNull', '')

        if new_text != text:
            file.write_text(new_text)


def build_minimal_cmd(project_root: Path, tmp_dir: str | None, copy_to_target: bool = False, run_tests: bool = False):
    import tempfile
    import shutil

    user_tmp = None
    if tmp_dir:
        user_tmp = Path(tmp_dir).expanduser().resolve()
        if user_tmp.exists():
            shutil.rmtree(user_tmp)
        # Do not create the directory here; shutil.copytree needs the destination to not exist.
        workdir = user_tmp
    else:
        # mkdtemp() creates the directory, but copytree() requires the destination to NOT exist.
        # So we create an isolated parent dir and copy into a fresh child.
        parent = Path(tempfile.mkdtemp(prefix='jstall-minimal-'))
        workdir = parent / 'work'

    print(f"→ Creating minimal build workspace at: {workdir}")

    try:
        _copytree(project_root, workdir)

        # Minimal filtering is done by the assembly descriptor (src/assembly/minimal.xml)

        pom_path = workdir / 'pom.xml'
        if not pom_path.exists():
            raise RuntimeError(f"pom.xml not found in {workdir}")

        _patch_pom_for_minimal(pom_path)
        _strip_nullable_annotations(workdir)

        if run_tests:
            print("→ Building & testing jstall-minimal ...")
            result = subprocess.run(['mvn', 'clean', 'test'], cwd=workdir, text=True)
            if result.returncode != 0:
                raise RuntimeError("Maven test failed")

            # Build artifacts (jar + script) after tests
            result = subprocess.run(['mvn', 'package', '-DskipTests'], cwd=workdir, text=True)
            if result.returncode != 0:
                raise RuntimeError("Maven package failed")
        else:
            print("→ Building jstall-minimal (skip tests) ...")
            result = subprocess.run(['mvn', 'clean', 'package', '-DskipTests'], cwd=workdir, text=True)
            if result.returncode != 0:
                raise RuntimeError("Maven build failed")

        # Note: assembly + execjar config produces target/jstall.jar and target/jstall
        jar = workdir / 'target' / 'jstall.jar'
        script = workdir / 'target' / 'jstall'

        # Strip Maven metadata from the fat jar (dependencies contribute META-INF/maven/*)
        _strip_maven_metadata_from_jar(jar)

        if jar.exists():
            print(f"✓ Built: {jar}")
        else:
            print(f"✓ Build finished. See: {workdir / 'target'}")

        if copy_to_target:
            dest_dir = project_root / 'target'
            dest_dir.mkdir(exist_ok=True)

            dest_jar = dest_dir / 'jstall-minimal.jar'
            dest_script = dest_dir / 'jstall-minimal'

            if jar.exists():
                shutil.copy2(jar, dest_jar)
                # Ensure copied artifact is also stripped (in case copy2 preserved older content)
                _strip_maven_metadata_from_jar(dest_jar)
                print(f"✓ Copied to: {dest_jar}")
            else:
                print(f"⚠ Minimal jar not found at {jar}, nothing to copy")

            if script.exists():
                shutil.copy2(script, dest_script)
                try:
                    dest_script.chmod(dest_script.stat().st_mode | 0o111)
                except Exception:
                    pass
                print(f"✓ Copied to: {dest_script}")
            else:
                print(f"⚠ Minimal script not found at {script}, nothing to copy")

        print("✓ Minimal build done")

    finally:
        if user_tmp is None:
            # workdir is a subdirectory of the mkdtemp-created parent
            shutil.rmtree(workdir.parent, ignore_errors=True)
        else:
            print(f"(tmp workspace kept at {user_tmp})")


def test_minimal_cmd(project_root: Path, tmp_dir: str | None):
    """Build and run tests for the minimal variant and copy its artifacts into target/."""
    build_minimal_cmd(project_root, tmp_dir=tmp_dir, copy_to_target=True, run_tests=True)


def deploy_minimal_cmd(project_root: Path, tmp_dir: str | None):
    """Build and deploy the minimal variant to Maven Central."""
    import tempfile
    import shutil

    user_tmp = None
    if tmp_dir:
        user_tmp = Path(tmp_dir).expanduser().resolve()
        if user_tmp.exists():
            shutil.rmtree(user_tmp)
        workdir = user_tmp
    else:
        parent = Path(tempfile.mkdtemp(prefix='jstall-minimal-deploy-'))
        workdir = parent / 'work'

    print(f"→ Creating minimal deploy workspace at: {workdir}")

    try:
        _copytree(project_root, workdir)

        pom_path = workdir / 'pom.xml'
        if not pom_path.exists():
            raise RuntimeError(f"pom.xml not found in {workdir}")

        _patch_pom_for_minimal(pom_path)
        _strip_nullable_annotations(workdir)

        print("→ Deploying jstall-minimal to Maven Central ...")
        result = subprocess.run(['mvn', 'clean', 'deploy', '-P', 'release', '-DskipTests'], cwd=workdir, text=True)
        if result.returncode != 0:
            raise RuntimeError("Maven deploy failed for jstall-minimal")

        print("✓ jstall-minimal deployed to Maven Central")

    finally:
        if user_tmp is None:
            shutil.rmtree(workdir.parent, ignore_errors=True)
        else:
            print(f"(tmp workspace kept at {user_tmp})")


class VersionBumper:
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.pom_xml = project_root / "pom.xml"
        self.main_java = project_root / "src/main/java/me/bechberger/jstall/Main.java"
        self.readme = project_root / "README.md"
        self.changelog = project_root / "CHANGELOG.md"
        self.jbang_catalog = project_root / "jbang-catalog.json"
        self.backup_dir = project_root / ".release-backup"
        self.backups_created = False

    def get_current_version(self) -> str:
        """Extract current version from pom.xml"""
        pom_content = self.pom_xml.read_text()
        match = re.search(r'<version>([\d.]+)</version>', pom_content)
        if not match:
            raise ValueError("Could not find version in pom.xml")
        return match.group(1)

    def parse_version(self, version: str) -> Tuple[int, int, int]:
        """Parse version string into (major, minor, patch)"""
        parts = version.split('.')
        if len(parts) != 3:
            raise ValueError(f"Invalid version format: {version}")
        return tuple(map(int, parts))

    def bump_minor(self, version: str) -> str:
        """Bump minor version (e.g., 0.0.0 -> 0.2.0)"""
        major, minor, patch = self.parse_version(version)
        return f"{major}.{minor + 1}.0"

    def bump_major(self, version: str) -> str:
        """Bump major version (e.g., 0.0.0 -> 1.0.0)"""
        major, minor, patch = self.parse_version(version)
        return f"{major + 1}.0.0"

    def bump_patch(self, version: str) -> str:
        """Bump patch version (e.g., 0.0.0 -> 0.1.1)"""
        major, minor, patch = self.parse_version(version)
        return f"{major}.{minor}.{patch + 1}"

    def update_pom_xml(self, old_version: str, new_version: str):
        """Update version in pom.xml"""
        content = self.pom_xml.read_text()
        # Replace first occurrence (project version)
        content = content.replace(
            f'<version>{old_version}</version>',
            f'<version>{new_version}</version>',
            1
        )
        self.pom_xml.write_text(content)
        print(f"✓ Updated pom.xml: {old_version} -> {new_version}")

    def update_main_java(self, old_version: str, new_version: str):
        """Update version in Main.java"""
        content = self.main_java.read_text()
        content = content.replace(
            f'version = "{old_version}"',
            f'version = "{new_version}"'
        )
        self.main_java.write_text(content)
        print(f"✓ Updated Main.java: {old_version} -> {new_version}")

    def update_readme(self, old_version: str, new_version: str):
        """Update version in README.md"""
        content = self.readme.read_text()
        content = content.replace(
            f'<version>{old_version}</version>',
            f'<version>{new_version}</version>'
        )
        self.readme.write_text(content)
        print(f"✓ Updated README.md: {old_version} -> {new_version}")

    def update_jbang_catalog(self, new_version: str):
        """Update version in jbang-catalog.json"""
        import json

        if not self.jbang_catalog.exists():
            print("⚠ No jbang-catalog.json found, skipping")
            return

        content = self.jbang_catalog.read_text()
        data = json.loads(content)

        # Update the script-ref URL to point to the new version
        if 'aliases' in data and 'jstall' in data['aliases']:
            old_url = data['aliases']['jstall']['script-ref']
            new_url = f'https://github.com/parttimenerd/jstall/releases/download/v{new_version}/jstall.jar'
            data['aliases']['jstall']['script-ref'] = new_url

            # Write back with proper formatting
            self.jbang_catalog.write_text(json.dumps(data, indent=2) + '\n')
            print(f"✓ Updated jbang-catalog.json: v{new_version}")
        else:
            print("⚠ jbang-catalog.json has unexpected structure, skipping update")

    def show_version_diff(self, old_version: str, new_version: str):
        """Show what would change in version files"""
        print("\n📝 File changes preview:")
        print(f"\n  pom.xml:")
        print(f"    - <version>{old_version}</version>")
        print(f"    + <version>{new_version}</version>")

        print(f"\n  Main.java:")
        print(f"    - version = \"{old_version}\"")
        print(f"    + version = \"{new_version}\"")

        print(f"\n  README.md:")
        print(f"    - <version>{old_version}</version>")
        print(f"    + <version>{new_version}</version>")

        print(f"\n  jbang-catalog.json:")
        print(f"    - releases/download/v{old_version}/jstall.jar")
        print(f"    + releases/download/v{new_version}/jstall.jar")

    def show_changelog_diff(self, version: str):
        """Show what would change in CHANGELOG.md"""
        if not self.changelog.exists():
            print("\n  CHANGELOG.md: (file does not exist)")
            return

        from datetime import datetime
        today = datetime.now().strftime('%Y-%m-%d')

        # Get the Unreleased entry
        entry = self.get_changelog_entry(version)

        print(f"\n  CHANGELOG.md:")
        print(f"    - ## [Unreleased]")
        print(f"    + ## [Unreleased]")
        print(f"    + ")
        print(f"    + ### Added")
        print(f"    + ### Changed")
        print(f"    + ")
        print(f"    + ## [{version}] - {today}")

        if entry:
            # Show first few lines of content that will move to new version
            lines = entry.split('\n')[:5]
            for line in lines:
                if line.strip():
                    truncated = line[:70] + ('...' if len(line) > 70 else '')
                    print(f"    + {truncated}")

    def get_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for Unreleased section"""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text()

        # Look for [Unreleased] section
        unreleased_match = re.search(
            r'## \[Unreleased\]\s*\n(.*?)(?=\n## \[|$)',
            content,
            re.DOTALL
        )

        if unreleased_match:
            entry = unreleased_match.group(1).strip()
            return entry

        return ""

    def get_version_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for a specific released version"""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text()

        # Look for specific version section
        version_pattern = rf'## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)'
        version_match = re.search(version_pattern, content, re.DOTALL)

        if version_match:
            entry = version_match.group(1).strip()
            # Remove empty section headers (headers with no content after them)
            lines = []
            header = None
            for line in entry.split('\n'):
                if line.startswith('###'):
                    header = line
                    continue
                if line.strip():
                    if header:
                        lines.append(header)
                        header = None
                    lines.append(line)

            return '\n'.join(lines) if lines else ""

        return ""

    def validate_changelog(self, version: str) -> bool:
        """Validate that changelog has entries for the version"""
        entry = self.get_changelog_entry(version)
        if not entry or len(entry) < 20:
            print("\n❌ ERROR: CHANGELOG.md must have content in [Unreleased] section")
            print("\nPlease add your changes to CHANGELOG.md under [Unreleased]:")
            print("  ### Added")
            print("  - New feature 1")
            print("  ### Changed")
            print("  - Change 1")
            print("  ### Fixed")
            print("  - Bug fix 1")
            return False
        return True

    def update_changelog(self, version: str):
        """Update CHANGELOG.md to release the Unreleased section"""
        if not self.changelog.exists():
            print("⚠ No CHANGELOG.md found, skipping")
            return

        content = self.changelog.read_text()

        # Get today's date
        from datetime import datetime
        today = datetime.now().strftime('%Y-%m-%d')

        # Replace [Unreleased] with version and add new Unreleased section
        unreleased_pattern = r'## \[Unreleased\]'
        version_section = f'## [Unreleased]\n\n### Added\n### Changed\n### Deprecated\n### Removed\n### Fixed\n### Security\n\n## [{version}] - {today}'

        content = re.sub(unreleased_pattern, version_section, content, count=1)

        # Update comparison links at bottom
        old_unreleased = re.search(r'\[Unreleased\]: (.+)/compare/v([\d.]+)\.\.\.HEAD', content)
        if old_unreleased:
            base_url = old_unreleased.group(1)
            old_version = old_unreleased.group(2)

            new_links = f'[Unreleased]: {base_url}/compare/v{version}...HEAD\n[{version}]: {base_url}/compare/v{old_version}...v{version}'
            content = re.sub(
                r'\[Unreleased\]: .+',
                new_links,
                content
            )

        self.changelog.write_text(content)
        print(f"✓ Updated CHANGELOG.md for version {version}")

    def create_github_release(self, version: str):
        """Create GitHub release using gh CLI and CHANGELOG.md"""
        tag = f'v{version}'

        # Check if gh CLI is available
        try:
            subprocess.run(['gh', '--version'], capture_output=True, check=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            print("⚠ GitHub CLI (gh) not found. Skipping GitHub release creation.")
            print("  Install with: brew install gh  (macOS)")
            print("  Or visit: https://cli.github.com/")
            return

        # Check authentication
        try:
            result = subprocess.run(['gh', 'auth', 'status'], capture_output=True, text=True)
            if result.returncode != 0:
                print("⚠ GitHub CLI not authenticated. Run: gh auth login")
                return
        except:
            print("⚠ Could not check GitHub CLI auth status")
            return

        # Ensure assets exist (especially important on CI automation):
        # If minimal artifacts are missing, generate them now so we never create a release
        # that claims to ship them but doesn't attach them.
        jar_path = self.project_root / 'target' / 'jstall.jar'
        script_path = self.project_root / 'target' / 'jstall'
        minimal_jar_path = self.project_root / 'target' / 'jstall-minimal.jar'
        minimal_script_path = self.project_root / 'target' / 'jstall-minimal'

        if not minimal_jar_path.exists() or not minimal_script_path.exists():
            print("→ jstall-minimal assets missing, building minimal variant for release assets ...")
            build_minimal_cmd(self.project_root, tmp_dir=None, copy_to_target=True, run_tests=False)

        # Re-check after build attempt
        missing = [p for p in (jar_path, script_path, minimal_jar_path, minimal_script_path) if not p.exists()]
        if missing:
            missing_str = "\n".join(f"  - {p}" for p in missing)
            raise RuntimeError(
                "Missing release assets after build attempt:\n"
                + missing_str
                + "\n\nRefusing to create a GitHub release without these assets."
            )

        # Get changelog entry for this specific version (after it's been released in CHANGELOG.md)
        changelog_entry = self.get_version_changelog_entry(version)
        if not changelog_entry:
            changelog_entry = f"Release {version}\n\nSee [CHANGELOG.md](https://github.com/parttimenerd/jstall/blob/main/CHANGELOG.md) for details."

        # Format release notes
        release_notes = f"""

{changelog_entry}

## Installation

### Maven
```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jstall</artifactId>
    <version>{version}</version>
</dependency>
```

### Direct Download
Download from the assets below:
- `jstall.jar` - Executable JAR file (requires Java 21+)
- `jstall` - Standalone launcher script (Unix/Linux/macOS)
- `jstall-minimal.jar` - Minimal executable JAR (SapMachine only, see note below)
- `jstall-minimal` - Minimal launcher script (SapMachine only, see note below)

**Important (jstall-minimal):**
The `jstall-minimal` artifacts are intended to be used only in combination with a recent [SapMachine](https://sapmachine.io) release.
These artifacts don't come with `asprof` or `libasyncprofiler` binaries bundled, so you need to ensure that your Java installation includes them,
SapMachine does.

**Usage:**
```bash
# Using the script (recommended for Unix-like systems)
chmod +x jstall
./jstall <pid>

# Using the JAR directly
java -jar jstall.jar <pid>
```
"""

        # Create release notes file
        notes_file = self.project_root / '.release-notes.md'
        notes_file.write_text(release_notes)

        try:
            assets = [
                str(jar_path) + '#jstall.jar',
                str(script_path) + '#jstall',
                str(minimal_jar_path) + '#jstall-minimal.jar',
                str(minimal_script_path) + '#jstall-minimal',
            ]

            self.run_command(
                ['gh', 'release', 'create', tag,
                 '--title', f'Release {version}',
                 '--notes-file', str(notes_file)] + assets,
                f"Creating GitHub release {tag} with {len(assets)} asset(s)"
            )
        finally:
            # Clean up notes file
            if notes_file.exists():
                notes_file.unlink()

    def create_backups(self):
        """Create backups of files that will be modified"""
        import shutil

        self.backup_dir.mkdir(exist_ok=True)

        files_to_backup = [
            self.pom_xml,
            self.main_java,
            self.readme,
            self.changelog,
            self.jbang_catalog
        ]

        for file in files_to_backup:
            if file.exists():
                backup_file = self.backup_dir / file.name
                shutil.copy2(file, backup_file)

        self.backups_created = True
        print("✓ Created backups of files")

    def restore_backups(self):
        """Restore files from backup"""
        import shutil

        if not self.backups_created or not self.backup_dir.exists():
            return

        print("\n⚠️  Restoring files from backup...")

        files_to_restore = [
            (self.backup_dir / "pom.xml", self.pom_xml),
            (self.backup_dir / "Main.java", self.main_java),
            (self.backup_dir / "README.md", self.readme),
            (self.backup_dir / "CHANGELOG.md", self.changelog),
            (self.backup_dir / "jbang-catalog.json", self.jbang_catalog)
        ]

        for backup_file, original_file in files_to_restore:
            if backup_file.exists():
                shutil.copy2(backup_file, original_file)
                print(f"  ✓ Restored {original_file.name}")

        print("✓ All files restored from backup")

    def cleanup_backups(self):
        """Remove backup directory"""
        import shutil

        if self.backup_dir.exists():
            shutil.rmtree(self.backup_dir)
            print("✓ Cleaned up backups")

    def run_command(self, cmd: list, description: str, check=True) -> subprocess.CompletedProcess:
        """Run a shell command"""
        print(f"\n→ {description}...")
        print(f"  $ {' '.join(cmd)}")
        result = subprocess.run(cmd, cwd=self.project_root, capture_output=True, text=True)

        if result.returncode != 0 and check:
            print(f"✗ Failed: {description}")
            print(f"  stdout: {result.stdout}")
            print(f"  stderr: {result.stderr}")

            # Restore backups on failure
            self.restore_backups()

            print("\n❌ Release failed. All changes have been reverted.")
            sys.exit(1)

        print(f"✓ {description}")
        return result

    def run_tests(self):
        """Run Maven tests"""
        self.run_command(
            ['mvn', 'clean', 'test'],
            "Running tests"
        )

    def sync_documentation(self):
        """Sync CLI help documentation to README"""
        sync_script = self.project_root / 'bin' / 'sync-documentation.py'
        if not sync_script.exists():
            print("⚠ sync-documentation.py not found, skipping documentation sync")
            return

        self.run_command(
            ['python3', str(sync_script)],
            "Syncing documentation"
        )

    def build_package(self):
        """Build Maven package"""
        self.run_command(
            ['mvn', 'clean', 'package'],
            "Building package"
        )

        # Also build and copy the minimal variant into this project's target/
        # so the release can attach both artifacts.
        try:
            build_minimal_cmd(self.project_root, tmp_dir=None, copy_to_target=True)
        except Exception as e:
            print(f"⚠ Failed to build/copy jstall-minimal artifacts: {e}")

    def deploy_release(self):
        """Deploy to Maven Central using release profile"""
        self.run_command(
            ['mvn', 'clean', 'deploy', '-P', 'release'],
            "Deploying jstall to Maven Central"
        )

        # Also deploy the minimal variant
        print("\n→ Deploying jstall-minimal to Maven Central...")
        try:
            deploy_minimal_cmd(self.project_root, tmp_dir=None)
        except Exception as e:
            print(f"⚠ Failed to deploy jstall-minimal: {e}")

    def git_commit(self, version: str):
        """Commit version changes"""
        self.run_command(
            ['git', 'add', 'pom.xml', 'src/main/java/me/bechberger/jstall/Main.java', 'README.md', 'CHANGELOG.md', 'jbang-catalog.json'],
            "Staging files"
        )
        self.run_command(
            ['git', 'commit', '-m', f'Bump version to {version}'],
            "Committing changes"
        )

    def git_tag(self, version: str):
        """Create git tag"""
        tag = f'v{version}'
        self.run_command(
            ['git', 'tag', '-a', tag, '-m', f'Release {version}'],
            f"Creating tag {tag}"
        )

    def git_push(self, push_tags: bool = True):
        """Push changes and tags"""
        self.run_command(
            ['git', 'push'],
            "Pushing commits"
        )
        if push_tags:
            self.run_command(
                ['git', 'push', '--tags'],
                "Pushing tags"
            )


def _strip_maven_metadata_from_jar(jar_path: Path):
    """Remove META-INF/maven/** entries from a jar/zip file in-place."""
    import zipfile
    import tempfile
    import os

    if not jar_path.exists():
        return

    with zipfile.ZipFile(jar_path, 'r') as zin:
        entries = zin.infolist()
        # Fast path: if there's no META-INF/maven, do nothing
        if not any(e.filename.startswith('META-INF/maven/') for e in entries):
            return

        fd, tmp_name = tempfile.mkstemp(prefix=jar_path.stem + '-', suffix='.jar')
        os.close(fd)
        tmp_path = Path(tmp_name)

        with zipfile.ZipFile(tmp_path, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
            for e in entries:
                if e.filename.startswith('META-INF/maven/'):
                    continue
                data = zin.read(e.filename)
                # Preserve timestamps/permissions where possible
                zi = zipfile.ZipInfo(e.filename)
                zi.date_time = e.date_time
                zi.external_attr = e.external_attr
                zi.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(zi, data)

    tmp_path.replace(jar_path)


def main():
    # Subcommands: keep old behaviour when no subcommand is given
    parser = argparse.ArgumentParser(
        description='Release helper for jstall',
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    subparsers = parser.add_subparsers(dest='command')

    # build-minimal
    p_min = subparsers.add_parser('build-minimal', help='Build minimal jstall-minimal variant')
    p_min.add_argument('--tmp', help='Use this directory as a temporary workspace (it will be deleted and recreated)')

    # test-minimal
    p_test_min = subparsers.add_parser('test-minimal', help='Build + run tests for minimal jstall-minimal variant')
    p_test_min.add_argument('--tmp', help='Use this directory as a temporary workspace (it will be deleted and recreated)')

    # deploy-minimal
    p_deploy_min = subparsers.add_parser('deploy-minimal', help='Deploy jstall-minimal to Maven Central')
    p_deploy_min.add_argument('--tmp', help='Use this directory as a temporary workspace (it will be deleted and recreated)')

    # Default release options (no subcommand)
    parser.add_argument('--major', action='store_true', help='Bump major version (x.0.0)')
    parser.add_argument('--minor', action='store_true', help='Bump minor version (0.x.0) [default]')
    parser.add_argument('--patch', action='store_true', help='Bump patch version (0.0.x)')
    parser.add_argument('--no-deploy', action='store_true', help='Skip deployment to Maven Central (deploy is default)')
    parser.add_argument('--no-github-release', action='store_true', help='Skip GitHub release creation (github-release is default)')
    parser.add_argument('--no-push', action='store_true', help='Skip pushing to git remote (push is default)')
    parser.add_argument('--skip-tests', action='store_true', help='Skip running tests')
    parser.add_argument('--dry-run', action='store_true', help='Show what would happen without making changes')

    args = parser.parse_args()

    # Determine project root
    script_path = Path(__file__).resolve()
    project_root = script_path.parent

    if args.command == 'build-minimal':
        build_minimal_cmd(project_root, args.tmp, copy_to_target=True)
        return

    if args.command == 'test-minimal':
        test_minimal_cmd(project_root, args.tmp)
        return

    if args.command == 'deploy-minimal':
        deploy_minimal_cmd(project_root, args.tmp)
        return

    # ---- existing release flow ----
    bumper = VersionBumper(project_root)

    # Get current version
    current_version = bumper.get_current_version()
    print(f"Current version: {current_version}")

    # Determine bump type
    if args.major:
        new_version = bumper.bump_major(current_version)
        bump_type = "major"
    elif args.patch:
        new_version = bumper.bump_patch(current_version)
        bump_type = "patch"
    else:
        new_version = bumper.bump_minor(current_version)
        bump_type = "minor"

    print(f"New version ({bump_type}): {new_version}")

    # Set defaults (deploy and github-release are ON by default)
    do_deploy = not args.no_deploy
    do_github_release = not args.no_github_release
    do_push = not args.no_push

    # Validate changelog before proceeding (unless dry-run)
    if not args.dry_run:
        if not bumper.validate_changelog(new_version):
            sys.exit(1)

    if args.dry_run:
        print("\n=== DRY RUN MODE ===")

        # Show file diffs
        bumper.show_version_diff(current_version, new_version)
        bumper.show_changelog_diff(new_version)

        # Show actions that would be taken
        print("\n📋 Actions that would be performed:")
        print("  • python3 bin/sync-documentation.py")
        if not args.skip_tests:
            print("  • mvn clean test")
        print("  • mvn clean package")
        if do_deploy:
            print("  • mvn clean deploy -P release")
        print(f"  • git add pom.xml Main.java README.md CHANGELOG.md")
        print(f"  • git commit -m 'Bump version to {new_version}'")
        print(f"  • git tag -a v{new_version} -m 'Release {new_version}'")
        if do_push:
            print("  • git push")
            print("  • git push --tags")
        if do_github_release:
            print(f"  • gh release create v{new_version} (with CHANGELOG entry + jstall.jar)")

        print("\n✓ No changes made (dry run)")
        return

    # Confirm
    step = 1
    print("\nThis will:")
    print(f"  {step}. Update version: {current_version} -> {new_version}")
    step += 1
    print(f"  {step}. Update CHANGELOG.md")
    step += 1
    print(f"  {step}. Sync documentation (CLI help)")
    step += 1

    if not args.skip_tests:
        print(f"  {step}. Run tests")
        step += 1

    print(f"  {step}. Build package")
    step += 1

    if do_deploy:
        print(f"  {step}. Deploy to Maven Central")
        step += 1

    print(f"  {step}. Commit and tag")
    step += 1

    if do_push:
        print(f"  {step}. Push to remote")
        step += 1

    if do_github_release:
        print(f"  {step}. Create GitHub release")
        step += 1

    response = input("\nContinue? [y/N] ")
    if response.lower() not in ['y', 'yes']:
        print("Aborted.")
        sys.exit(0)

    try:
        # Create backups before making any changes
        print("\n=== Creating backups ===")
        bumper.create_backups()

        # Update version files
        print("\n=== Updating version files ===")
        bumper.update_pom_xml(current_version, new_version)
        bumper.update_main_java(current_version, new_version)
        bumper.update_readme(current_version, new_version)
        bumper.update_jbang_catalog(new_version)
        bumper.update_changelog(new_version)

        # Sync documentation
        print("\n=== Syncing documentation ===")
        bumper.sync_documentation()

        # Run tests
        if not args.skip_tests:
            print("\n=== Running tests ===")
            bumper.run_tests()
        else:
            print("\n⚠ Skipping tests")

        # Build package
        print("\n=== Building package ===")
        bumper.build_package()

        # Deploy
        if do_deploy:
            print("\n=== Deploying to Maven Central ===")
            print("⚠ Make sure you have configured:")
            print("  - GPG key for signing")
            print("  - Maven settings.xml with OSSRH credentials")
            response = input("\nReady to deploy? [y/N] ")
            if response.lower() not in ['y', 'yes']:
                print("Skipping deployment.")
                do_deploy = False
            else:
                bumper.deploy_release()

        # Git operations
        print("\n=== Git operations ===")
        bumper.git_commit(new_version)
        bumper.git_tag(new_version)

        if do_push:
            bumper.git_push(push_tags=True)

        # GitHub release
        if do_github_release:
            print("\n=== Creating GitHub release ===")
            bumper.create_github_release(new_version)

        # Cleanup backups after successful release
        bumper.cleanup_backups()

    except KeyboardInterrupt:
        print("\n\n⚠️  Release interrupted by user")
        bumper.restore_backups()
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ Unexpected error: {e}")
        bumper.restore_backups()
        raise

    # Summary
    print("\n" + "="*60)
    print(f"✓ Successfully released version {new_version}")
    print("="*60)

    print("\nCompleted:")
    print(f"  ✓ Version bumped: {current_version} -> {new_version}")
    print(f"  ✓ CHANGELOG.md updated")
    print(f"  ✓ Tests passed" if not args.skip_tests else "  ⊘ Tests skipped")
    print(f"  ✓ Package built")
    print(f"  ✓ Deployed to Maven Central" if do_deploy else "  ⊘ Deployment skipped")
    print(f"  ✓ Git commit and tag created")
    print(f"  ✓ Pushed to remote" if do_push else "  ⊘ Push skipped")
    print(f"  ✓ GitHub release created" if do_github_release else "  ⊘ GitHub release skipped")

    print(f"\nArtifacts:")
    print(f"  • target/jstall.jar (executable JAR)")
    print(f"  • target/jstall (launcher script)")
    print(f"  • target/jstall-{new_version}.jar")
    print(f"  • target/jstall-{new_version}-sources.jar")
    print(f"  • target/jstall-{new_version}-javadoc.jar")

    if do_github_release:
        print(f"\n📦 GitHub Release:")
        print(f"  https://github.com/parttimenerd/jstall/releases/tag/v{new_version}")

    if do_deploy:
        print(f"\n📦 Maven Central:")
        print(f"  https://central.sonatype.com/artifact/me.bechberger/jstall/{new_version}")


if __name__ == '__main__':
    main()