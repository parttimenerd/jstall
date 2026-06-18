#!/bin/bash
# demo.sh — Single entry point for all demo operations.
#
# Usage:
#   ./scripts/demo.sh start       — Full buggy demo: kill → reset → build → run → open browser
#   ./scripts/demo.sh fix         — Apply fix: kill → fix → build → run
#   ./scripts/demo.sh stop        — Kill the running app
#   ./scripts/demo.sh status      — Show current code version & app state
#   ./scripts/demo.sh trigger     — Trigger the deadlock via curl
#   ./scripts/demo.sh notify [N]  — Fire notification (optionally after N seconds)
#   ./scripts/demo.sh check       — Pre-flight checklist validation
#   ./scripts/demo.sh llm [tier]  — Start local LLM server (fast/medium/slow)
#   ./scripts/demo.sh help        — Show this help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
info() { echo -e "  ${BLUE}ℹ${NC} $1"; }
warn() { echo -e "  ${YELLOW}⚠${NC} $1"; }

# --- Subcommands ---

cmd_start() {
    echo -e "${BOLD}▶ Starting demo (buggy version)${NC}"
    echo ""
    "$SCRIPT_DIR/demo-reset.sh" --run
    echo ""
    echo -e "${GREEN}Demo ready!${NC} Trigger deadlock:"
    echo "  curl http://localhost:4004/api/reserve-seat"
    echo "  open http://localhost:4004/travel_processor/webapp/index.html"
    echo ""
    echo -e "Pre-stage notification: ${YELLOW}./scripts/demo.sh notify 30${NC}"
}

cmd_fix() {
    echo -e "${BOLD}▶ Applying fix & restarting${NC}"
    echo ""
    "$SCRIPT_DIR/demo-reset.sh" --run --fix
    echo ""
    echo -e "${GREEN}Fixed version running!${NC} Verify:"
    echo "  curl http://localhost:4004/api/reserve-seat"
}

cmd_stop() {
    echo -e "${BOLD}▶ Stopping app${NC}"
    local pids
    pids=$(pgrep -f "spring-boot.*sflight\|SFlightApplication" 2>/dev/null || true)
    local port_pids
    port_pids=$(lsof -ti:4004 2>/dev/null || true)
    pids=$(echo -e "$pids\n$port_pids" | sort -u | grep -v '^$' || true)
    if [ -n "$pids" ]; then
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 2
        echo "$pids" | xargs kill -9 2>/dev/null || true
        ok "App stopped (PIDs: $pids)"
    else
        info "No running app found"
    fi
}

cmd_status() {
    echo -e "${BOLD}▶ Demo Status${NC}"
    echo ""

    # Code version
    local version
    version=$("$SCRIPT_DIR/demo-reset.sh" --status 2>/dev/null | awk '{print $NF}')
    if [ "$version" = "buggy" ]; then
        echo -e "  Code:  ${RED}buggy${NC} (deadlock active)"
    elif [ "$version" = "fixed" ]; then
        echo -e "  Code:  ${GREEN}fixed${NC}"
    else
        echo -e "  Code:  ${YELLOW}unknown${NC}"
    fi

    # App running?
    if curl -s -o /dev/null -w '' http://localhost:4004/ 2>/dev/null; then
        echo -e "  App:   ${GREEN}running${NC} (port 4004)"
    else
        echo -e "  App:   ${RED}not running${NC}"
    fi

    # LLM server?
    if curl -s -o /dev/null http://127.0.0.1:8080/health 2>/dev/null; then
        echo -e "  LLM:   ${GREEN}running${NC} (port 8080)"
    else
        echo -e "  LLM:   ${YELLOW}not running${NC}"
    fi
    echo ""
}

cmd_trigger() {
    echo -e "${BOLD}▶ Triggering deadlock${NC}"
    if ! curl -s -o /dev/null -w '' http://localhost:4004/ 2>/dev/null; then
        fail "App not running on port 4004. Start it first: ./scripts/demo.sh start"
        exit 1
    fi
    echo ""
    local response
    response=$(curl -s http://localhost:4004/api/reserve-seat)
    echo "  Response: $response"
    echo ""
}

cmd_notify() {
    local delay="${1:-0}"
    "$SCRIPT_DIR/demo-notification.sh" "$delay"
}

cmd_check() {
    echo -e "${BOLD}▶ Pre-flight Checklist${NC}"
    echo ""
    local errors=0

    # Code version
    local version
    version=$("$SCRIPT_DIR/demo-reset.sh" --status 2>/dev/null | awk '{print $NF}')
    if [ "$version" = "buggy" ]; then
        ok "Code is in buggy state"
    else
        fail "Code is '$version' — run: ./scripts/demo.sh start"
        ((errors++)) || true
    fi

    # Java available
    if command -v java &>/dev/null; then
        ok "Java installed ($(java -version 2>&1 | head -1 | awk -F'"' '{print $2}'))"
    else
        fail "Java not found"
        ((errors++)) || true
    fi

    # Maven available
    if command -v mvn &>/dev/null; then
        ok "Maven installed"
    else
        fail "Maven not found"
        ((errors++)) || true
    fi

    # terminal-notifier
    if command -v terminal-notifier &>/dev/null; then
        ok "terminal-notifier installed"
    else
        fail "terminal-notifier not found — brew install terminal-notifier"
        ((errors++)) || true
    fi

    # VS Code extension compiled
    if [ -f "$ROOT_DIR/dist/extension.js" ] || [ -f "$ROOT_DIR/out/extension.js" ]; then
        ok "Extension compiled"
    else
        warn "Extension not compiled — run: npm run compile"
    fi

    # App running check
    if curl -s -o /dev/null -w '' http://localhost:4004/ 2>/dev/null; then
        ok "App running on port 4004"
    else
        info "App not running (start with: ./scripts/demo.sh start)"
    fi

    # CF CLI
    if command -v cf &>/dev/null; then
        ok "CF CLI installed"
        if cf target &>/dev/null 2>&1; then
            ok "CF logged in"
        else
            warn "CF not logged in — run: cf login"
        fi
    else
        info "CF CLI not installed (only needed for remote demo)"
    fi

    # LLM server
    if curl -s -o /dev/null http://127.0.0.1:8080/health 2>/dev/null; then
        ok "Local LLM server running"
    else
        info "Local LLM not running (only needed for epilogue)"
    fi

    echo ""
    if [ "$errors" -eq 0 ]; then
        echo -e "  ${GREEN}${BOLD}All critical checks passed!${NC}"
    else
        echo -e "  ${RED}${BOLD}$errors critical issue(s) found.${NC}"
    fi
    echo ""
}

cmd_llm() {
    local tier="${1:---medium}"
    # Normalize: accept with or without --
    tier="${tier#--}"
    echo -e "${BOLD}▶ Starting local LLM (${tier})${NC}"
    exec "$SCRIPT_DIR/00-launch-llm.sh" "--$tier"
}

cmd_help() {
    cat <<'EOF'

  ╔══════════════════════════════════════════════════════════╗
  ║  JStall Demo — One-Shot JVM Diagnostics                 ║
  ╚══════════════════════════════════════════════════════════╝

  Usage: ./scripts/demo.sh <command> [options]

  Commands:
    start          Full buggy demo setup (kill → reset → build → run)
    fix            Apply fix & restart (kill → fix → build → run)
    stop           Kill the running app
    status         Show current state (code version, app, LLM)
    trigger        Trigger the deadlock via curl
    notify [N]     Fire Slack notification (after N seconds)
    check          Run pre-flight checklist
    llm [tier]     Start local LLM (fast/medium/slow)
    help           Show this help

  Quick start:
    ./scripts/demo.sh check          # verify everything is ready
    ./scripts/demo.sh start          # start the buggy app
    ./scripts/demo.sh notify 30      # fire notification in 30s
    ./scripts/demo.sh trigger        # trigger the deadlock
    ./scripts/demo.sh fix            # apply fix & restart

EOF
}

# --- Main ---

case "${1:-help}" in
    start)           cmd_start ;;
    fix)             cmd_fix ;;
    stop)            cmd_stop ;;
    status)          cmd_status ;;
    trigger)         cmd_trigger ;;
    notify)          cmd_notify "${2:-0}" ;;
    check|preflight) cmd_check ;;
    llm)             cmd_llm "${2:-}" ;;
    help|-h|--help)  cmd_help ;;
    *)
        echo -e "${RED}Unknown command: $1${NC}" >&2
        echo "Run './scripts/demo.sh help' for usage." >&2
        exit 1
        ;;
esac
