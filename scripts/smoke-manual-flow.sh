#!/usr/bin/env bash
set -euo pipefail

# 기본 API 서버 주소.
# 다른 포트/환경에 붙일 때는 BASE_URL 환경변수로 덮어쓴다.
BASE_URL="${BASE_URL:-http://localhost:8080}"

# snapshot worker가 Redis -> PostgreSQL snapshot 을 저장할 대기 시간.
# local profile 기준 30초 worker를 감안해 기본값을 35초로 둔다.
WAIT_SNAPSHOT_SECONDS="${WAIT_SNAPSHOT_SECONDS:-35}"

# 생성 리소스 이름 기본값.
# 매 실행마다 timestamp를 붙여 중복 충돌을 피한다.
PROJECT_NAME="${PROJECT_NAME:-manual-demo-$(date +%s)}"
LEADERBOARD_NAME="${LEADERBOARD_NAME:-manual-board}"
USER_EXTERNAL_ID="${USER_EXTERNAL_ID:-manual-user-$(date +%s)}"

# /events 호출 시 사용할 delta score 목록.
# 인자를 넘기면 그 값을 그대로 사용하고, 안 넘기면 기본 3건(100, 50, 25)을 사용한다.
# 예:
#   bash scripts/smoke-manual-flow.sh 200 300 400
if (($# > 0)); then
  DELTA_SCORES=("$@")
else
  DELTA_SCORES=(100 50 25)
fi

# jq/curl/uuidgen은 이 스크립트 실행에 필수다.
# 특히 jq는 응답 JSON에서 id/rawKey를 뽑는 데 사용한다.
require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[smoke] required command not found: $cmd" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd uuidgen

# 공통 HTTP 호출 헬퍼.
# body가 있으면 JSON 요청으로 보내고, 없으면 단순 GET처럼 사용한다.
# 4번째 인자 이후는 추가 헤더를 그대로 넘긴다.
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
  printf '\n[%s] %s\n' "smoke" "$1"
}

# 1. 테스트용 유저 생성.
# /events 의 userId는 숫자 long 이므로, externalId가 아니라 응답의 내부 id를 저장한다.
print_section "1. create user"
USER_RESPONSE="$(json_request POST /users "$(cat <<JSON
{
  "externalId":"$USER_EXTERNAL_ID"
}
JSON
)")"
echo "$USER_RESPONSE" | jq
USER_ID="$(echo "$USER_RESPONSE" | jq -r '.id')"

# 2. 방금 만든 유저로 admin login.
# 현재 구현 가정상 admin auth는 기존 user externalId 기반 JWT 로그인이다.
# 여기서 받은 accessToken은 관리 API(/projects, /leaderboards, /admin/api-keys)에 사용한다.
print_section "2. admin login"
LOGIN_RESPONSE="$(json_request POST /auth/login "$(cat <<JSON
{
  "externalId":"$USER_EXTERNAL_ID"
}
JSON
)")"
echo "$LOGIN_RESPONSE" | jq
ADMIN_JWT="$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken')"

# 3. 프로젝트 생성.
# POST /projects 응답에는 defaultApiKey가 같이 오므로,
# 여기서 rawKey를 뽑아 이후 /events Authorization 헤더에 사용한다.
print_section "3. create project with default api key"
PROJECT_RESPONSE="$(json_request POST /projects "$(cat <<JSON
{
  "name":"$PROJECT_NAME"
}
JSON
)" \
  -H "Authorization: Bearer $ADMIN_JWT")"
echo "$PROJECT_RESPONSE" | jq
PROJECT_ID="$(echo "$PROJECT_RESPONSE" | jq -r '.id')"
API_KEY_ID="$(echo "$PROJECT_RESPONSE" | jq -r '.defaultApiKey.id')"
RAW_API_KEY="$(echo "$PROJECT_RESPONSE" | jq -r '.defaultApiKey.rawKey')"

# 4. 위에서 만든 프로젝트 아래에 리더보드 생성.
# 이후 Hot Path / Cold Path 검증은 이 leaderboardId를 기준으로 한다.
print_section "4. create leaderboard"
LEADERBOARD_RESPONSE="$(json_request POST /leaderboards "$(cat <<JSON
{
  "projectId":"$PROJECT_ID",
  "name":"$LEADERBOARD_NAME"
}
JSON
)" \
  -H "Authorization: Bearer $ADMIN_JWT")"
echo "$LEADERBOARD_RESPONSE" | jq
LEADERBOARD_ID="$(echo "$LEADERBOARD_RESPONSE" | jq -r '.id')"

# 5. 이벤트 적재.
# 매 요청마다 새로운 Idempotency-Key를 만들어 replay가 아닌 신규 처리로 넣는다.
# DELTA_SCORES 배열을 바꾸면 누적 점수 패턴을 쉽게 수정할 수 있다.
print_section "5. post events with default api key"
for delta in "${DELTA_SCORES[@]}"; do
  IDEMPOTENCY_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  EVENT_RESPONSE="$(json_request POST /events "$(cat <<JSON
{
  "leaderboardId":"$LEADERBOARD_ID",
  "userId":"$USER_ID",
  "deltaScore":$delta
}
JSON
)" \
    -H "Authorization: Bearer $RAW_API_KEY" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY")"
  echo "$EVENT_RESPONSE" | jq
done

# 6. Redis Hot Path 기준 Top N 확인.
# 지금 적재한 이벤트가 랭킹 조회 API에 즉시 반영되는지 보는 단계다.
print_section "6. hot path top ranks"
TOPS_RESPONSE="$(json_request GET "/leaderboards/$LEADERBOARD_ID/tops?offset=0&limit=10" "")"
echo "$TOPS_RESPONSE" | jq

# 7. 특정 유저 단건 조회.
# 참여 유저는 score/rank가 보이고, 미참여 유저는 score=0, rank=null 이어야 한다.
print_section "7. hot path user rank"
USER_RANK_RESPONSE="$(json_request GET "/leaderboards/$LEADERBOARD_ID/users/$USER_ID" "")"
echo "$USER_RANK_RESPONSE" | jq

# 8. audit stream 내부 상태 확인.
# 현재 범위에서는 consumer group이 없어서 pendingEntries=0,
# consumerLag는 누적 stream 길이로 해석한다.
print_section "8. internal streams status"
STREAMS_STATUS_RESPONSE="$(json_request GET /internal/streams/status "")"
echo "$STREAMS_STATUS_RESPONSE" | jq

# 9. snapshot worker가 한 번 돌 시간을 기다린다.
# worker 주기를 바꿨다면 WAIT_SNAPSHOT_SECONDS도 같이 조정하면 된다.
print_section "9. wait for snapshot worker ($WAIT_SNAPSHOT_SECONDS seconds)"
sleep "$WAIT_SNAPSHOT_SECONDS"

# 10. snapshot worker 내부 상태 확인.
# 마지막 성공 시각과 lag를 보면 worker가 실제로 돌았는지 빠르게 확인할 수 있다.
print_section "10. snapshot status"
SNAPSHOT_STATUS_RESPONSE="$(json_request GET /internal/snapshot/status "")"
echo "$SNAPSHOT_STATUS_RESPONSE" | jq

# 11. PostgreSQL Cold Path snapshot 조회.
# Hot Path에서 본 랭킹이 snapshot에도 반영됐는지 비교하는 단계다.
print_section "11. cold path snapshot entries"
SNAPSHOT_ENTRIES_RESPONSE="$(json_request GET "/internal/snapshots/$LEADERBOARD_ID/entries?offset=0&limit=10" "")"
echo "$SNAPSHOT_ENTRIES_RESPONSE" | jq

# 마지막에 핵심 식별자를 다시 출력해
# 추가 curl/DBeaver/Swagger 검증에 바로 재사용할 수 있게 한다.
print_section "summary"
cat <<SUMMARY
userId=$USER_ID
projectId=$PROJECT_ID
leaderboardId=$LEADERBOARD_ID
apiKeyId=$API_KEY_ID
adminJwt=$ADMIN_JWT
rawApiKey=$RAW_API_KEY

검증 포인트:
- /auth/login 으로 받은 JWT로 /projects, /leaderboards 가 생성되어야 한다.
- POST /projects 응답의 defaultApiKey.rawKey로 /events 호출이 성공해야 한다.
- /leaderboards/{leaderboardId}/tops 와 /users/{userId}가 Hot Path 값을 보여야 한다.
- /internal/streams/status 가 audit stream 누적 상태를 보여야 한다.
- snapshot worker가 돈 뒤 /internal/snapshots/{leaderboardId}/entries 가 Cold Path 값을 보여야 한다.
SUMMARY
