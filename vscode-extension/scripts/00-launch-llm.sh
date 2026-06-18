#!/usr/bin/env bash
# 00-launch-llm.sh — Launch local LLM server (background)
# Usage: ./00-launch-llm.sh [--fast|--medium|--slow]
# Default: fast

set -euo pipefail

# Configure HuggingFace cache to persist models forever
export HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
export HF_HUB_CACHE="${HF_HUB_CACHE:-$HF_HOME/hub}"
mkdir -p "$HF_HUB_CACHE"

usage() {
  cat <<USAGE
Usage: $0 [--slow] [--medium] [--fast]

Options:
  --slow         Use the slower, larger model (bartowski/Qwen_Qwen3.5-27B-GGUF:Q8_0)
  --medium       Use the medium model (AaryanK/Qwen3.5-9B-GGUF:Q8_0)
  --fast         Use the faster default model (Qwen/Qwen3-1.7B-GGUF:Q8_0)
  -h, --help     Show this help and exit
USAGE
}

# Default mode
MODE="fast"

# CLI parsing
while [[ $# -gt 0 ]]; do
  case "$1" in
    --slow|slow)
      MODE="slow"
      shift
      ;;
    --medium|medium)
      MODE="medium"
      shift
      ;;
    --fast|fast)
      MODE="fast"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# Start the selected mode
echo "Model cache directory: $HF_HUB_CACHE"
if [[ "$MODE" == "fast" ]]; then
  echo "Starting llama-server (fast mode)..."
  llama-server -hf Qwen/Qwen3-1.7B-GGUF:Q8_0
elif [[ "$MODE" == "medium" ]]; then
  echo "Starting llama-server (medium mode)..."
  llama-server -hf AaryanK/Qwen3.5-9B-GGUF:Q8_0
else
  echo "Starting llama-server (slow mode)..."
  llama-server -hf bartowski/Qwen_Qwen3.5-27B-GGUF:Q8_0
fi
