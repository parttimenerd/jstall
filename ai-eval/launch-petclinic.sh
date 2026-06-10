#!/bin/bash
# Launcher for petclinic-running scenario.
# Usage: launch-petclinic.sh <ready-file>
# Starts spring-petclinic, prints PID to stdout, touches ready-file when JVM is up.
set -e
READY="$1"
JAR="/Users/i560383_1/code/experiments/test-order/third-party/spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar"
LOG="/tmp/jstall-petclinic-$$.log"

# Start petclinic. Use a random port to avoid clashes.
PORT=$((9000 + RANDOM % 1000))
java -jar "$JAR" --server.port=$PORT > "$LOG" 2>&1 &
PID=$!
disown 2>/dev/null || true

# Wait up to 60s for "Started PetClinicApplication" or port readiness
for i in $(seq 1 600); do
    if grep -q "Started PetClinic" "$LOG" 2>/dev/null; then
        echo "ready" > "$READY"
        echo "$PID"
        exit 0
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "petclinic exited early; log at $LOG" >&2
        exit 1
    fi
    sleep 0.1
done

echo "petclinic did not start in 60s; log at $LOG" >&2
kill -9 "$PID" 2>/dev/null
exit 1
