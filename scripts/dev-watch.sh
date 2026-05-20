#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WATCH_PID=""

cleanup() {
  if [[ -n "$WATCH_PID" ]] && kill -0 "$WATCH_PID" >/dev/null 2>&1; then
    kill "$WATCH_PID" >/dev/null 2>&1 || true
    wait "$WATCH_PID" 2>/dev/null || true
  fi
}

trap cleanup EXIT INT TERM

./gradlew --console=plain -t classes &
WATCH_PID="$!"

./gradlew --console=plain bootRun
