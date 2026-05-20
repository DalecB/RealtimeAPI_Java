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

PROJECT_ID="10000000-0000-0000-0000-000000000001"
HOT_LEADERBOARD_ID="20000000-0000-0000-0000-000000000001"
TIE_LEADERBOARD_ID="20000000-0000-0000-0000-000000000002"
EMPTY_LEADERBOARD_ID="20000000-0000-0000-0000-000000000003"

HOT_KEY="lb:{${HOT_LEADERBOARD_ID}}:z"
TIE_KEY="lb:{${TIE_LEADERBOARD_ID}}:z"
EMPTY_KEY="lb:{${EMPTY_LEADERBOARD_ID}}:z"

echo "[seed] postgres/redis 컨테이너를 시작합니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" up -d "$POSTGRES_SERVICE" "$REDIS_SERVICE" >/dev/null

echo "[seed] 이전 snapshot demo 시드를 정리합니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$POSTGRES_SERVICE" \
  psql -U "$DB_USER" -d "$DB_NAME" <<SQL
-- 이 스크립트가 사용하는 고정 project/leaderboard를 먼저 지우면
-- snapshot_batches, snapshot_entries, api_keys, usage_stats는 FK CASCADE로 함께 정리된다.
DELETE FROM projects
WHERE id = '${PROJECT_ID}';

-- 데모 전용 user range도 매번 비워서 이전 실행 흔적이 남지 않게 한다.
DELETE FROM users
WHERE id BETWEEN 101 AND 110;
SQL

echo "[seed] PostgreSQL에 users / project / leaderboards 데이터를 넣습니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$POSTGRES_SERVICE" \
  psql -U "$DB_USER" -d "$DB_NAME" <<SQL
INSERT INTO users (id, external_id) VALUES
  (101, 'seed-user-101'),
  (102, 'seed-user-102'),
  (103, 'seed-user-103'),
  (104, 'seed-user-104'),
  (105, 'seed-user-105'),
  (106, 'seed-user-106'),
  (107, 'seed-user-107'),
  (108, 'seed-user-108'),
  (109, 'seed-user-109'),
  (110, 'seed-user-110')
ON CONFLICT (id) DO UPDATE
SET external_id = EXCLUDED.external_id;

SELECT setval(
  pg_get_serial_sequence('users', 'id'),
  GREATEST((SELECT COALESCE(MAX(id), 1) FROM users), 1),
  true
);

INSERT INTO projects (id, admin_id, name) VALUES
  ('${PROJECT_ID}', 101, 'snapshot-seed-project')
ON CONFLICT (id) DO UPDATE
SET admin_id = EXCLUDED.admin_id,
    name = EXCLUDED.name;

INSERT INTO leaderboards (id, project_id, name) VALUES
  ('${HOT_LEADERBOARD_ID}', '${PROJECT_ID}', 'snapshot-hot-board'),
  ('${TIE_LEADERBOARD_ID}', '${PROJECT_ID}', 'snapshot-tie-board'),
  ('${EMPTY_LEADERBOARD_ID}', '${PROJECT_ID}', 'snapshot-empty-board')
ON CONFLICT (id) DO UPDATE
SET project_id = EXCLUDED.project_id,
    name = EXCLUDED.name;
SQL

echo "[seed] Redis에 leaderboard ZSET 데이터를 넣습니다..."
"${COMPOSE_CMD[@]}" -f "$ROOT_DIR/docker-compose.yml" exec -T "$REDIS_SERVICE" \
  redis-cli <<REDIS
DEL ${HOT_KEY}
DEL ${TIE_KEY}
DEL ${EMPTY_KEY}
ZADD ${HOT_KEY} 1000 101 950 102 900 103 850 104 800 105 750 106
ZADD ${TIE_KEY} 2000 107 1500 108 1500 109 1200 110
REDIS

cat <<EOF

[seed] 완료
projectId=${PROJECT_ID}
leaderboardId(hot)=${HOT_LEADERBOARD_ID}
leaderboardId(tie)=${TIE_LEADERBOARD_ID}
leaderboardId(empty)=${EMPTY_LEADERBOARD_ID}

검증 포인트:
1. hot board: 일반 Top-N snapshot 저장 확인
2. tie board: competition ranking(1,2,2,4) 확인
3. empty board: Empty Guard / snapshot_skip_total 확인

권장 실행 순서:
1. SPRING_PROFILES_ACTIVE=local SPRING_DATA_REDIS_PORT=6370 ./gradlew bootRun
2. bash scripts/seed-snapshot-demo.sh
3. local profile로 worker 활성화 상태 확인
4. snapshot_batches / snapshot_entries / /internal/snapshot/status 확인

수동 API 검증 흐름:
- POST /projects -> defaultApiKey 사용 기준 흐름은 docs/MANUAL_VERIFICATION.md 참고
EOF
