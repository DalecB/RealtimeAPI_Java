#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/erd}"

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  echo "docker compose 또는 docker-compose를 찾지 못했습니다." >&2
  exit 1
fi

if [[ "${OSTYPE:-}" == linux* ]]; then
  DEFAULT_DB_HOST="host.docker.internal"
  NEED_HOST_GATEWAY=true
else
  DEFAULT_DB_HOST="host.docker.internal"
  NEED_HOST_GATEWAY=false
fi

DB_HOST="${DB_HOST:-$DEFAULT_DB_HOST}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-realtime_ranking}"
DB_USER="${DB_USER:-app}"
DB_PASSWORD="${DB_PASSWORD:-app}"
DB_SCHEMA="${DB_SCHEMA:-public}"

mkdir -p "$OUT_DIR"

"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" up -d postgres >/dev/null

docker_cmd=(docker run --rm)
if [[ "$NEED_HOST_GATEWAY" == true ]]; then
  docker_cmd+=(--add-host host.docker.internal:host-gateway)
fi
docker_cmd+=(
  -v "$OUT_DIR:/output"
  schemaspy/schemaspy:latest
  -t pgsql
  -host "$DB_HOST"
  -port "$DB_PORT"
  -db "$DB_NAME"
  -u "$DB_USER"
  -p "$DB_PASSWORD"
  -s "$DB_SCHEMA"
)

"${docker_cmd[@]}"

echo "ERD 생성 완료: $OUT_DIR/index.html"
