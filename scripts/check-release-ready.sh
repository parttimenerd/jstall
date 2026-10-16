#!/usr/bin/env bash
# check-release-ready.sh — verify all version strings are consistent before a release.
# Usage: ./scripts/check-release-ready.sh [expected-version]
#   If expected-version is omitted, reads it from pom.xml.
set -euo pipefail

cd "$(dirname "$0")/.."

POM_VERSION=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d '[:space:]')
EXPECTED="${1:-$POM_VERSION}"

errors=0

check() {
    local desc="$1"
    local file="$2"
    local pattern="$3"
    if grep -q "$pattern" "$file"; then
        echo "  OK  $desc"
    else
        echo "FAIL  $desc (pattern: $pattern)"
        errors=$((errors + 1))
    fi
}

echo "Checking version consistency for v$EXPECTED"
echo

check "pom.xml project version"          pom.xml                                                           "<version>$EXPECTED</version>"
check "Main.java @Command version"        src/main/java/me/bechberger/jstall/Main.java                     "version = \"$EXPECTED\""
check "Main.java VERSION constant"        src/main/java/me/bechberger/jstall/Main.java                     "VERSION = \"$EXPECTED\""

# Check jstall-minimal.jar if it exists
MINIMAL_JAR="target/jstall-minimal.jar"
if [ -f "$MINIMAL_JAR" ]; then
    JAR_VERSION=$(java -jar "$MINIMAL_JAR" --version 2>&1 | grep -Eo '^[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
    if [ "$JAR_VERSION" = "$EXPECTED" ]; then
        echo "  OK  target/jstall-minimal.jar reports $JAR_VERSION"
    else
        echo "FAIL  target/jstall-minimal.jar reports '$JAR_VERSION', expected '$EXPECTED'"
        echo "      Run: python3.11 release.py build-minimal"
        errors=$((errors + 1))
    fi
else
    echo " N/A  target/jstall-minimal.jar not built yet (run: python3.11 release.py build-minimal)"
fi

echo
if [ "$errors" -eq 0 ]; then
    echo "All version checks passed."
else
    echo "$errors check(s) failed."
    exit 1
fi
