#!/bin/sh

# Based on the launcher.sh script from async-profiler
# https://github.com/async-profiler/async-profiler/blob/2a4f329cbae8f849642d4cd74b766ced79ed3557/src/launcher/launcher.sh

if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
    echo "jstall 0.1.0"
    exit 0
fi

JAVA_OPTS=""

# Process JVM options that start with -D, -X, -agent, or -J
while [ $# -gt 0 ]; do
    case "$1" in
        -D*|-X*)
            if [ ${#1} -gt 2 ]; then
                JAVA_OPTS="$JAVA_OPTS $1"
            fi
            ;;
        -agent*)
            JAVA_OPTS="$JAVA_OPTS $1"
            ;;
        -J*)
            opt=$(echo "$1" | cut -c3-)
            JAVA_OPTS="$JAVA_OPTS $opt"
            ;;
        *)
            break
            ;;
    esac
    shift
done

# 1. Try JAVA_HOME
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    exec "$JAVA_HOME/bin/java" $JAVA_OPTS -jar "$0" "$@"
fi

# 2. Try PATH
if command -v java >/dev/null 2>&1; then
    exec java $JAVA_OPTS -jar "$0" "$@"
fi

# 3. Try /etc/alternatives/java
if [ -x "/etc/alternatives/java" ]; then
    exec "/etc/alternatives/java" $JAVA_OPTS -jar "$0" "$@"
fi

# 4. Try common JVM directories
if [ "$(uname -s)" = "Darwin" ]; then
    JVM_DIR="/Library/Java/JavaVirtualMachines"
    CONTENTS_HOME="/Contents/Home"
else
    JVM_DIR="/usr/lib/jvm"
    CONTENTS_HOME=""
fi

if [ -d "$JVM_DIR" ]; then
    for JVM in "$JVM_DIR"/*; do
        if [ -d "$JVM" ]; then
            JAVA_EXE="$JVM$CONTENTS_HOME/bin/java"
            if [ -x "$JAVA_EXE" ]; then
                exec "$JAVA_EXE" $JAVA_OPTS -jar "$0" "$@"
            fi
        fi
    done
fi

echo "No JDK found. Set JAVA_HOME or ensure java executable is on the PATH" >&2
exit 1