#!/bin/bash
# demo-reset.sh — Reset the demo to the buggy (deadlocked) state.
#
# Usage:
#   ./scripts/demo-reset.sh              — swap to buggy code
#   ./scripts/demo-reset.sh --fix        — swap to fixed code
#   ./scripts/demo-reset.sh --status     — show which version is active
#   ./scripts/demo-reset.sh --run        — reset + rebuild + restart app
#   ./scripts/demo-reset.sh --run --fix  — fix + rebuild + restart app

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SRC="$ROOT_DIR/cap-sflight/srv/src/main/java/com/sap/cap/sflight/processor"
ACTIVE="$SRC/SeatReservationService.java"
BUGGY="$ACTIVE.buggy"
FIXED="$ACTIVE.fixed"
CAP_DIR="$ROOT_DIR/cap-sflight"

# Parse flags (order-independent)
DO_RUN=false
DO_FIX=false
DO_STATUS=false
DO_HELP=false

for arg in "$@"; do
    case "$arg" in
        --run)    DO_RUN=true ;;
        --fix)    DO_FIX=true ;;
        --status) DO_STATUS=true ;;
        --reset|"") ;;
        -h|--help) DO_HELP=true ;;
        *) echo "Unknown flag: $arg" >&2; echo "Usage: $0 [--reset|--fix|--status|--run] [--fix]" >&2; exit 1 ;;
    esac
done

# Detect current state
detect_version() {
    if grep -q '@Scheduled' "$ACTIVE" 2>/dev/null; then
        echo "buggy"
    elif grep -q 'BOOKING_LOCK' "$ACTIVE" 2>/dev/null; then
        echo "fixed"
    else
        echo "unknown"
    fi
}

# Kill any running spring-boot process in cap-sflight
kill_app() {
    local pids
    pids=$(pgrep -f "spring-boot.*sflight\|SFlightApplication" 2>/dev/null || true)
    # Also catch anything on port 4004
    local port_pids
    port_pids=$(lsof -ti:4004 2>/dev/null || true)
    pids=$(echo -e "$pids\n$port_pids" | sort -u | grep -v '^$' || true)
    if [ -n "$pids" ]; then
        echo "⏹  Stopping running app (PIDs: $pids)…"
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 2
        # Force-kill if still alive
        echo "$pids" | xargs kill -9 2>/dev/null || true
        sleep 1
    fi
}

# Rebuild srv module (spring-boot:run does this, but explicit is clearer)
rebuild_app() {
    echo "🔨 Rebuilding srv module…"
    cd "$CAP_DIR"
    mvn compile -Denforcer.skip=true -pl srv -q
    echo "   Build successful."
}

# Start the app in background
start_app() {
    echo "🚀 Starting cap-sflight app…"
    cd "$CAP_DIR"
    mvn spring-boot:run -Denforcer.skip=true -pl srv &
    local mvn_pid=$!
    echo "   Maven PID: $mvn_pid"

    # Wait for app to start (look for Tomcat port message)
    echo -n "   Waiting for startup"
    for i in $(seq 1 30); do
        if curl -s -o /dev/null -w '' http://localhost:4004/ 2>/dev/null; then
            echo " ✓ (ready on port 4004)"
            return 0
        fi
        echo -n "."
        sleep 2
    done
    echo " timeout (app may still be starting)"
}

case "true" in
    "$DO_HELP")
        echo "Usage: $0 [--reset|--fix|--status|--run] [--fix]"
        echo ""
        echo "  (no args), --reset   Swap to buggy version"
        echo "  --fix                Swap to fixed version"
        echo "  --status             Show which version is active"
        echo "  --run                Kill → swap → rebuild → restart"
        echo "  --run --fix          Kill → fix → rebuild → restart"
        echo "  --fix --run          Same as --run --fix"
        exit 0
        ;;
    "$DO_STATUS")
        version=$(detect_version)
        echo "Current version: $version"
        ;;
    "$DO_RUN")
        # --run [--fix] or --fix --run
        kill_app
        if [ "$DO_FIX" = true ]; then
            "$0" --fix
        else
            "$0" --reset
        fi
        rebuild_app
        start_app
        echo ""
        echo "🎯 Trigger deadlock:  curl http://localhost:4004/api/reserve-seat"
        ;;
    "$DO_FIX")
        if [ ! -f "$FIXED" ]; then
            echo "Error: $FIXED not found" >&2
            exit 1
        fi
        cp "$FIXED" "$ACTIVE"
        echo "✅ Swapped to FIXED version (global lock ordering, no cross-service synchronized)"
        ;;
    *)
        # Default: reset to buggy
        if [ ! -f "$BUGGY" ]; then
            echo "Error: $BUGGY not found" >&2
            exit 1
        fi
        # Save current fixed version if not already saved
        if [ ! -f "$FIXED" ]; then
            cp "$ACTIVE" "$FIXED"
            echo "Saved current version as .fixed backup"
        fi
        cp "$BUGGY" "$ACTIVE"
        echo "🐛 Swapped to BUGGY version (3-service circular deadlock via synchronized cross-calls)"
        echo ""
        echo "Next steps:"
        echo "  1. Full auto:  ./scripts/demo-reset.sh --run"
        echo "  2. Or manual:  cd cap-sflight && mvn spring-boot:run -Denforcer.skip=true -pl srv"
        echo "  3. Trigger:    curl http://localhost:4004/api/reserve-seat"
        ;;
esac
