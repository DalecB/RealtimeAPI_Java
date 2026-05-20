#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "$#" -eq 0 ]; then
  cat <<'EOF' >&2
Usage:
  bash scripts/run-k6.sh run k6/t1-hot-path-write.js

Examples:
  USER_COUNT=300 bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-300.json k6/t1-hot-path-write.js
  USER_COUNT=1000 WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t3-1000.json k6/t3-mixed-workload.js
EOF
  exit 1
fi

if command -v k6 >/dev/null 2>&1; then
  exec k6 "$@"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "k6가 없고 docker도 찾지 못했습니다. k6를 설치하거나 Docker Desktop을 실행하세요." >&2
  exit 1
fi

compose_network=""
if docker inspect realtime-api >/dev/null 2>&1; then
  compose_network="$(docker inspect realtime-api --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' | head -n 1 | tr -d '[:space:]')"
fi

base_url="${BASE_URL:-}"
if [ -z "$base_url" ]; then
  if [ -n "$compose_network" ]; then
    base_url="http://app:8080"
  else
    base_url="http://host.docker.internal:8080"
  fi
fi

docker_args=(
  run
  --rm
  -i
  -v "$ROOT_DIR:/work"
  -w /work
)

if [ -n "$compose_network" ]; then
  docker_args+=(--network "$compose_network")
else
  docker_args+=(--add-host "host.docker.internal:host-gateway")
fi

env_names=(
  BASE_URL
  USER_COUNT
  DELTA_SCORE
  WRITE_RPS
  READ_RPS
  DURATION
  BOOTSTRAP_READY_TIMEOUT_MS
  BOOTSTRAP_READY_INTERVAL_MS
  BENCHMARK_RATE_LIMIT_PER_SEC
  BENCHMARK_DAILY_QUOTA
  K6_LOG_LEVEL
)

for name in "${env_names[@]}"; do
  if [ "$name" = "BASE_URL" ]; then
    docker_args+=(-e "BASE_URL=$base_url")
    continue
  fi

  if [ -n "${!name:-}" ]; then
    docker_args+=(-e "$name=${!name}")
  fi
done

echo "k6가 로컬에 없어 Docker로 실행합니다. BASE_URL=$base_url" >&2
exec docker "${docker_args[@]}" grafana/k6:latest "$@"
