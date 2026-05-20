#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PROJECT_NAME="${PROJECT_NAME:-breaker-demo-$(date +%s)}"
LEADERBOARD_NAME="${LEADERBOARD_NAME:-breaker-board}"
USER_EXTERNAL_ID="${USER_EXTERNAL_ID:-breaker-user-$(date +%s)}"
ATTEMPTS="${ATTEMPTS:-11}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-8}"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[breaker] required command not found: $cmd" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd uuidgen
require_cmd docker

json_request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  shift 3 || true

  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$BASE_URL$path" \
      -H 'Content-Type: application/json' \
      "$@" \
      -d "$body"
  else
    curl -sS -X "$method" "$BASE_URL$path" \
      "$@"
  fi
}

print_section() {
  printf '\n[%s] %s\n' "breaker" "$1"
}

cleanup() {
  docker compose start redis >/dev/null 2>&1 || true
}

trap cleanup EXIT

print_section "1. create user"
USER_RESPONSE="$(json_request POST /users "$(cat <<JSON
{
  "externalId":"$USER_EXTERNAL_ID"
}
JSON
)")"
echo "$USER_RESPONSE" | jq
USER_ID="$(echo "$USER_RESPONSE" | jq -r '.id')"

print_section "2. admin login"
LOGIN_RESPONSE="$(json_request POST /auth/login "$(cat <<JSON
{
  "externalId":"$USER_EXTERNAL_ID"
}
JSON
)")"
echo "$LOGIN_RESPONSE" | jq
ADMIN_JWT="$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken')"

print_section "3. create project and leaderboard"
PROJECT_RESPONSE="$(json_request POST /projects "$(cat <<JSON
{
  "name":"$PROJECT_NAME"
}
JSON
)" -H "Authorization: Bearer $ADMIN_JWT")"
echo "$PROJECT_RESPONSE" | jq
PROJECT_ID="$(echo "$PROJECT_RESPONSE" | jq -r '.id')"
RAW_API_KEY="$(echo "$PROJECT_RESPONSE" | jq -r '.defaultApiKey.rawKey')"

LEADERBOARD_RESPONSE="$(json_request POST /leaderboards "$(cat <<JSON
{
  "projectId":"$PROJECT_ID",
  "name":"$LEADERBOARD_NAME"
}
JSON
)" -H "Authorization: Bearer $ADMIN_JWT")"
echo "$LEADERBOARD_RESPONSE" | jq
LEADERBOARD_ID="$(echo "$LEADERBOARD_RESPONSE" | jq -r '.id')"

print_section "4. warmup one successful event before Redis shutdown"
WARMUP_IDEMPOTENCY_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
json_request POST /events "$(cat <<JSON
{
  "leaderboardId":"$LEADERBOARD_ID",
  "userId":"$USER_ID",
  "deltaScore":1
}
JSON
)" \
  -H "Authorization: Bearer $RAW_API_KEY" \
  -H "Idempotency-Key: $WARMUP_IDEMPOTENCY_KEY" | jq

print_section "5. stop redis"
docker compose stop redis

print_section "6. call /events repeatedly until breaker opens"
for ((i=1; i<=ATTEMPTS; i++)); do
  IDEMPOTENCY_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  BODY_FILE="$(mktemp)"
  HEADER_FILE="$(mktemp)"

  STATUS_CODE="$(curl -sS -o "$BODY_FILE" -D "$HEADER_FILE" -w '%{http_code}' \
    --connect-timeout 3 \
    --max-time "$REQUEST_TIMEOUT_SECONDS" \
    -X POST "$BASE_URL/events" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $RAW_API_KEY" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
    -d "$(cat <<JSON
{
  "leaderboardId":"$LEADERBOARD_ID",
  "userId":"$USER_ID",
  "deltaScore":1
}
JSON
)")"

  ERROR_CODE="$(jq -r '.code // empty' "$BODY_FILE" 2>/dev/null || true)"
  RETRY_AFTER="$(grep -i '^Retry-After:' "$HEADER_FILE" | awk '{print $2}' | tr -d '\r' || true)"
  if [[ "$STATUS_CODE" == "000" ]]; then
    echo "attempt=$i status=TIMEOUT code=${ERROR_CODE:-NONE} retryAfter=${RETRY_AFTER:-NONE}"
  else
    echo "attempt=$i status=$STATUS_CODE code=${ERROR_CODE:-NONE} retryAfter=${RETRY_AFTER:-NONE}"
  fi

  rm -f "$BODY_FILE" "$HEADER_FILE"
done

print_section "7. breaker status"
json_request GET /internal/circuit-breaker/status "" | jq

print_section "expected"
cat <<SUMMARY
- 초기 몇 번은 Redis 호출 실패로 5xx가 날 수 있다.
- slidingWindowSize=10 기준으로 실패가 누적되면 breaker 상태가 OPEN 이어야 한다.
- OPEN 이후 응답은 503, Retry-After: 10 이어야 한다.
- 스크립트 종료 시 Redis는 자동으로 다시 시작된다.
SUMMARY
