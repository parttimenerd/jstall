#!/bin/bash
# Launcher for petclinic-running-with-load: petclinic + a sustained HTTP load generator
# in another thread group, so we get realistic web-app traffic during analysis.
set -e
READY="$1"
JAR="/Users/i560383_1/code/experiments/test-order/third-party/spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar"
LOG="/tmp/jstall-petclinic-load-$$.log"

PORT=$((9000 + RANDOM % 1000))
java -jar "$JAR" --server.port=$PORT > "$LOG" 2>&1 &
PID=$!
disown 2>/dev/null || true

for i in $(seq 1 600); do
    if grep -q "Started PetClinic" "$LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "petclinic exited early; log at $LOG" >&2
        exit 1
    fi
    sleep 0.1
done

if ! grep -q "Started PetClinic" "$LOG" 2>/dev/null; then
    echo "petclinic did not start in 60s" >&2
    kill -9 "$PID" 2>/dev/null
    exit 1
fi

# Generate continuous load — 10 parallel curls hammering owners endpoint.
# Critical: redirect stdout/stderr in the subshell, otherwise the curl loops
# inherit the parent script's stdout (captured by the eval harness via $(...))
# and pin it open forever, hanging the harness.
for i in 1 2 3 4 5 6 7 8 9 10; do
    (while kill -0 "$PID" 2>/dev/null; do
        curl -s -o /dev/null "http://127.0.0.1:$PORT/owners?lastName=" 2>/dev/null
        curl -s -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null
    done) </dev/null >/dev/null 2>&1 &
    disown 2>/dev/null || true
done

# Let load steady-state
sleep 2

echo "ready" > "$READY"
echo "$PID"
