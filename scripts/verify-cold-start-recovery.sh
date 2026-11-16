#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
LEADERBOARD_ID="${LEADERBOARD_ID:-}"
WAIT_SECONDS="${WAIT_SECONDS:-30}"

# 서버 기동 시 시드된 관리자 계정. 기본값은 docker-compose/local 프로필의 개발용 관리자다.
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-local-admin-password}"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[recovery] required command not found: $cmd" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd docker

if [[ -z "$LEADERBOARD_ID" ]]; then
  echo "[recovery] LEADERBOARD_ID is required" >&2
  echo "example: LEADERBOARD_ID=your-leaderboard-id bash scripts/verify-cold-start-recovery.sh" >&2
  exit 1
fi

print_section() {
  printf '\n[%s] %s\n' "recovery" "$1"
}

wait_for_health() {
  local deadline=$((SECONDS + WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl -fsS "$BASE_URL/actuator/health" | jq -e '.status == "UP"' >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# /internal/**는 관리자 JWT가 필요하다. 토큰은 앱 재시작 뒤에도 같은 서명 키로 유효하다.
ADMIN_JWT="$(curl -sS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{externalId: $u, password: $p}')" | jq -r '.accessToken')"
if [[ -z "$ADMIN_JWT" || "$ADMIN_JWT" == "null" ]]; then
  echo "[recovery] admin login failed: ADMIN_USERNAME/ADMIN_PASSWORD가 서버 시드 관리자와 같은지 확인하세요" >&2
  exit 1
fi

print_section "1. ensure latest snapshot exists"
SNAPSHOT_RESPONSE="$(curl -sS -H "Authorization: Bearer $ADMIN_JWT" "$BASE_URL/internal/snapshots/$LEADERBOARD_ID/entries?offset=0&limit=10")"
echo "$SNAPSHOT_RESPONSE" | jq
ROW_COUNT="$(echo "$SNAPSHOT_RESPONSE" | jq -r '.total // 0')"
if [[ "$ROW_COUNT" == "0" ]]; then
  echo "[recovery] snapshot entries are empty; create events and wait for snapshot first" >&2
  exit 1
fi

print_section "2. delete redis hot path key and warm key (simulate Redis data loss)"
REDIS_KEY="lb:{$LEADERBOARD_ID}:z"
WARM_KEY="lb:{$LEADERBOARD_ID}:warm"
docker compose exec -T redis redis-cli DEL "$REDIS_KEY" "$WARM_KEY"

echo "[recovery] redis keys deleted: $REDIS_KEY $WARM_KEY"

print_section "3. restart app to trigger cold start runner"
docker compose restart app >/dev/null

print_section "4. wait for app health"
if ! wait_for_health; then
  echo "[recovery] app did not become healthy within ${WAIT_SECONDS}s" >&2
  exit 1
fi
curl -sS "$BASE_URL/actuator/health" | jq

print_section "5. verify breaker status after startup"
curl -sS -H "Authorization: Bearer $ADMIN_JWT" "$BASE_URL/internal/circuit-breaker/status" | jq

print_section "6. verify hot path is restored from latest snapshot"
curl -sS "$BASE_URL/leaderboards/$LEADERBOARD_ID/tops?offset=0&limit=10" | jq

print_section "expected"
cat <<SUMMARY
- 앱 재시작 시 cold start recovery runner가 최신 snapshot을 읽는다.
- warm 키가 없으면 snapshot_entries 기준으로 ZADD 복구하고 warm 키를 쓴다.
- 복구 전까지 write는 503 RANKING_REBUILDING, read는 source=SNAPSHOT 이다.
- 최종적으로 /leaderboards/{leaderboardId}/tops 에 데이터가 다시 보여야 한다.
SUMMARY
