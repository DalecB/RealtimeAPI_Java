#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml")
RESTORE_CONSUMER_NAME="${EVENTS_RELAY_CONSUMER_NAME:-realtime-api}"

RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]' | cut -c1-8)"
LEADERBOARD_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
PROJECT_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
STREAM_KEY="lb:{${LEADERBOARD_ID}}:events"
GROUP="audit-relay"
MIN_IDLE_MS=600000
BEFORE_IDLE_MS=$((MIN_IDLE_MS - 10000))
USER_ID=""

cleanup() {
  "${COMPOSE[@]}" up -d kafka >/dev/null 2>&1 || true
  "${COMPOSE[@]}" exec -T redis redis-cli DEL "$STREAM_KEY" >/dev/null 2>&1 || true
  if [[ -n "$USER_ID" ]]; then
    "${COMPOSE[@]}" exec -T postgres psql -U app -d realtime_ranking -c \
      "DELETE FROM projects WHERE id = '${PROJECT_ID}'; DELETE FROM users WHERE id = ${USER_ID};" >/dev/null 2>&1 || true
  fi
  EVENTS_RELAY_CONSUMER_NAME="$RESTORE_CONSUMER_NAME" "${COMPOSE[@]}" up -d --force-recreate app >/dev/null 2>&1 || true
}
trap cleanup EXIT

pending() {
  "${COMPOSE[@]}" exec -T redis redis-cli --raw XPENDING "$STREAM_KEY" "$GROUP" - + 1 2>/dev/null || true
}

pending_owner() {
  pending | sed -n '2p' | tr -d '\r'
}

pending_idle_ms() {
  pending | sed -n '3p' | tr -d '\r'
}

pending_count() {
  "${COMPOSE[@]}" exec -T redis redis-cli --raw XPENDING "$STREAM_KEY" "$GROUP" 2>/dev/null \
    | sed -n '1p' | tr -d '\r'
}

db_count() {
  "${COMPOSE[@]}" exec -T postgres psql -U app -d realtime_ranking -tAc \
    "SELECT count(*) FROM audit_events WHERE leaderboard_id = '${LEADERBOARD_ID}' AND event_id = '${EVENT_ID}';" \
    | tr -d '[:space:]'
}

echo "[relay-handoff] start infrastructure and relay A"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 180 postgres redis kafka
EVENTS_RELAY_CONSUMER_NAME=relay-a "${COMPOSE[@]}" up -d --build --force-recreate app
for ((i = 0; i < 90; i++)); do
  curl -fsS http://localhost:8080/actuator/health >/dev/null && break
  sleep 2
done
(( i < 90 )) || { echo "FAIL: relay A did not start" >&2; exit 1; }

echo "[relay-handoff] create isolated fixture"
USER_ID="$("${COMPOSE[@]}" exec -T postgres psql -U app -d realtime_ranking -tAc \
  "INSERT INTO users (external_id) VALUES ('relay-handoff-${RUN_ID}') RETURNING id;" \
  | sed -n '1p' | tr -d '[:space:]')"
"${COMPOSE[@]}" exec -T postgres psql -U app -d realtime_ranking <<SQL
INSERT INTO projects (id, admin_id, name) VALUES ('${PROJECT_ID}', ${USER_ID}, 'relay-handoff-${PROJECT_ID}');
INSERT INTO leaderboards (id, project_id, name) VALUES ('${LEADERBOARD_ID}', '${PROJECT_ID}', 'relay-handoff-${LEADERBOARD_ID}');
SQL

echo "[relay-handoff] stop Kafka and create one stream entry"
"${COMPOSE[@]}" stop kafka
EVENT_ID="$("${COMPOSE[@]}" exec -T redis redis-cli --raw XADD "$STREAM_KEY" '*' \
  type new \
  userId "$USER_ID" \
  delta 1 \
  apiKeyId "$USER_ID" \
  idempotencyKey "$PROJECT_ID" | tr -d '\r')"

for ((i = 0; i < 60; i++)); do
  [[ "$(pending_owner)" == "relay-a" ]] && break
  sleep 2
done
[[ "$(pending_owner)" == "relay-a" ]] || { echo "FAIL: relay A did not own the PEL entry" >&2; exit 1; }

echo "[relay-handoff] relay A owns event $EVENT_ID; send SIGKILL"
APP_CONTAINER_ID="$("${COMPOSE[@]}" ps -q app)"
[[ -n "$APP_CONTAINER_ID" ]] || { echo "FAIL: Compose app container not found" >&2; exit 1; }
docker kill --signal KILL "$APP_CONTAINER_ID" >/dev/null

echo "[relay-handoff] restart Kafka and start relay B"
"${COMPOSE[@]}" up -d --wait --wait-timeout 180 kafka
EVENTS_RELAY_CONSUMER_NAME=relay-b "${COMPOSE[@]}" up -d --force-recreate app

while true; do
  OWNER="$(pending_owner)"
  IDLE_MS="$(pending_idle_ms)"
  [[ "$OWNER" == "relay-a" ]] || { echo "FAIL: claimed before 10 minutes" >&2; exit 1; }
  (( IDLE_MS >= BEFORE_IDLE_MS )) && break
  sleep 5
done
echo "[relay-handoff] PASS: still owned by relay A at ${IDLE_MS}ms idle"

for ((i = 0; i < 30; i++)); do
  [[ "$(pending_count)" == "0" ]] && break
  sleep 5
done
[[ "$(pending_count)" == "0" ]] || { echo "FAIL: relay B did not clear the PEL" >&2; exit 1; }

for ((i = 0; i < 30; i++)); do
  [[ "$(db_count)" == "1" ]] && break
  sleep 2
done
[[ "$(db_count)" == "1" ]] || { echo "FAIL: PostgreSQL row count is not 1" >&2; exit 1; }

echo "[relay-handoff] PASS: relay B claimed after 10 minutes; PEL=0, PostgreSQL=1"
