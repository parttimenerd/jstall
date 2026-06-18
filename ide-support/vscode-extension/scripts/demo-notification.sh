#!/bin/bash
# demo-notification.sh — Fire a macOS notification simulating a Slack alert.
#
# Usage:
#   ./scripts/demo-notification.sh          — interactive: press 'r' to fire
#   ./scripts/demo-notification.sh 30       — fire after 30 seconds
#   ./scripts/demo-notification.sh --help   — show help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ICON="$SCRIPT_DIR/slack-icon.png"
SENDER_APP="$SCRIPT_DIR/SlackNotify.app"
DELAY="${1:-0}"

# Handle --help
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    echo "Usage: $0 [delay_seconds]"
    echo ""
    echo "  (no args)    Interactive mode — press 'r' to fire, 'q' to quit"
    echo "  <seconds>    Fire notification after N seconds (non-interactive)"
    echo ""
    echo "Tip: Use before starting the demo act to pre-stage the alert:"
    echo "  ./scripts/demo-notification.sh 30"
    exit 0
fi

fire_notification() {
    local sender_flag=()
    if [ -d "$SENDER_APP" ]; then
        sender_flag=(-sender "com.demo.slack-notifier")
    fi
    terminal-notifier \
        -title "#prod-incidents" \
        -subtitle "Michael Scott" \
        -message "@channel 🔥 47 users unable to reserve seats in last 5 min. Backend returns monitor contention errors. SeatInventoryService looks stuck. Can someone check the JVM?" \
        -sound Blow \
        "${sender_flag[@]}"
    echo "→ Notification sent!"
}

# If delay specified, fire after N seconds without waiting for keypress
if [ "$DELAY" -gt 0 ] 2>/dev/null; then
    echo "→ Notification in ${DELAY}s..."
    sleep "$DELAY"
    fire_notification
    exit 0
fi

echo "╔══════════════════════════════════════════════════════╗"
echo "║  Demo Notification Trigger                          ║"
echo "║  Press 'r' to fire Slack alert and exit.            ║"
echo "║  Press 'q' to quit without notification.            ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

while true; do
    read -rsn1 key
    case "$key" in
        r|R)
            fire_notification
            exit 0
            ;;
        q|Q)
            echo "Bye."
            exit 0
            ;;
    esac
done
