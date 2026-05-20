#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  echo "docker compose 또는 docker-compose를 찾지 못했습니다." >&2
  exit 1
fi

POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
REDIS_SERVICE="${REDIS_SERVICE:-redis}"
DB_NAME="${DB_NAME:-realtime_ranking}"
DB_USER="${DB_USER:-app}"

PROJECT_NAME_LIKE="${PROJECT_NAME_LIKE:-k6-project-%}"
ADMIN_EXTERNAL_ID_LIKE="${ADMIN_EXTERNAL_ID_LIKE:-k6-admin-%}"
USER_EXTERNAL_ID_LIKE="${USER_EXTERNAL_ID_LIKE:-k6-user-%}"

LEADERBOARD_IDS_FILE="$(mktemp)"
trap 'rm -f "$LEADERBOARD_IDS_FILE"' EXIT

echo "[cleanup] postgres/redis 컨테이너를 시작합니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" up -d "$POSTGRES_SERVICE" "$REDIS_SERVICE" >/dev/null

echo "[cleanup] 정리 대상 leaderboard id를 수집합니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$POSTGRES_SERVICE" \
  psql -U "$DB_USER" -d "$DB_NAME" -At <<SQL > "$LEADERBOARD_IDS_FILE"
select l.id
from leaderboards l
join projects p on p.id = l.project_id
where p.name like '${PROJECT_NAME_LIKE}';
SQL

leaderboard_count="$(grep -cve '^[[:space:]]*$' "$LEADERBOARD_IDS_FILE" || true)"
echo "[cleanup] 대상 leaderboard 수: ${leaderboard_count}"

if [ "$leaderboard_count" -gt 0 ]; then
  echo "[cleanup] Redis leaderboard 키를 정리합니다..."
  while IFS= read -r leaderboard_id; do
    [ -n "$leaderboard_id" ] || continue
    "${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$REDIS_SERVICE" sh -lc "
      redis-cli DEL 'lb:{${leaderboard_id}}:z' 'lb:{${leaderboard_id}}:events' >/dev/null
      redis-cli --scan --pattern 'lb:{${leaderboard_id}}:idem:*' | xargs -r redis-cli DEL >/dev/null
    "
  done < "$LEADERBOARD_IDS_FILE"
else
  echo "[cleanup] 정리할 Redis leaderboard 키가 없습니다."
fi

echo "[cleanup] PostgreSQL 테스트 데이터를 정리합니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$POSTGRES_SERVICE" \
  psql -U "$DB_USER" -d "$DB_NAME" <<SQL
DELETE FROM projects
WHERE name LIKE '${PROJECT_NAME_LIKE}';

DELETE FROM users
WHERE external_id LIKE '${ADMIN_EXTERNAL_ID_LIKE}'
   OR external_id LIKE '${USER_EXTERNAL_ID_LIKE}';
SQL

echo "[cleanup] 정리 결과를 확인합니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$POSTGRES_SERVICE" \
  psql -U "$DB_USER" -d "$DB_NAME" <<SQL
SELECT id, name
FROM projects
WHERE name LIKE '${PROJECT_NAME_LIKE}';

SELECT id, external_id
FROM users
WHERE external_id LIKE '${ADMIN_EXTERNAL_ID_LIKE}'
   OR external_id LIKE '${USER_EXTERNAL_ID_LIKE}';
SQL

echo "[cleanup] 완료"
