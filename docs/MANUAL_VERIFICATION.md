# Manual Verification

적용한 PRD 기준:
- `POST /events`는 API key 인증 + rate limit/quota를 통과해야 한다.
- 관리 API는 JWT 인증을 통과해야 한다. 이 구현에서는 기존 `users.externalId` 기반 login으로 JWT를 발급한다. (가정)
- `POST /projects`로 프로젝트를 만들면 현재 로그인한 유저 id가 `adminId`로 저장되고, 바로 사용할 기본 API key가 함께 발급된다.
- 랭킹 읽기는 Hot Path(Redis), 스냅샷은 Cold Path(PostgreSQL)로 검증한다.

이 문서는 수동 검증 흐름을 `POST /projects -> defaultApiKey 사용` 기준으로 정리한다.
기존 `scripts/seed-snapshot-demo.sh`는 snapshot 파이프라인 검증용 고정 데이터 시드이고,
아래 흐름은 실제 API 사용 시나리오를 손으로 따라가는 용도다.

전체 플로우를 한 번에 돌리고 싶으면 아래 smoke 스크립트를 먼저 써도 된다.

```bash
bash scripts/smoke-manual-flow.sh
```

Circuit Breaker를 Redis 장애 상태로 직접 검증하려면 아래 전용 스크립트를 사용한다.

```bash
bash scripts/verify-circuit-breaker-open.sh
```

Cold Start Recovery를 수동으로 검증하려면 아래 스크립트를 사용한다.

```bash
LEADERBOARD_ID=your-leaderboard-id bash scripts/verify-cold-start-recovery.sh
```

전제:
- 앱이 이미 떠 있어야 한다.
- `jq`, `curl`, `uuidgen`이 필요하다.
- 기본 대기 시간은 35초라 snapshot worker 결과까지 같이 본다.

## 1. 앱 실행

```bash
docker compose up -d postgres redis
SPRING_PROFILES_ACTIVE=local SPRING_DATA_REDIS_PORT=6370 ./gradlew bootRun
```

기대 결과:
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6370`
- local profile 기준으로 snapshot worker가 활성화된다.

## 2. 유저 생성

```bash
curl -s -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{
    "externalId":"manual-user-101"
  }'
```

기대 결과:
- 응답에 `id`가 생성된다.
- 이후 `/events` 호출에서는 이 `id`를 `userId`로 사용한다.

예시 응답:

```json
{
  "id": 1,
  "externalId": "manual-user-101",
  "createdAt": "2026-03-19T01:00:00Z"
}
```

## 3. admin login

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{
    "externalId":"manual-user-101"
  }'
```

기대 결과:
- 응답에 `accessToken`이 생성된다.
- 이후 관리 API(`POST /projects`, `POST /leaderboards`, `POST /admin/api-keys`)는 이 JWT를 `Authorization: Bearer ...`로 사용한다.

예시 응답:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresAt": "2026-03-19T02:00:00Z",
  "userId": 1,
  "externalId": "manual-user-101"
}
```

## 4. 프로젝트 생성 + 기본 API key 발급

```bash
curl -s -X POST http://localhost:8080/projects \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"manual-demo-project"
  }'
```

기대 결과:
- 응답에 `id`가 생성된다.
- `adminId`가 현재 로그인한 유저 id와 같아야 한다.
- `defaultApiKey.rawKey`가 함께 내려온다.
- 이 raw key를 이후 `/events` 요청의 `Authorization` 헤더에 사용한다.

예시 응답:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "adminId": 1,
  "name": "manual-demo-project",
  "createdAt": "2026-03-19T01:01:00Z",
  "defaultApiKey": {
    "id": 1,
    "rawKey": "rk_...",
    "keyPrefix": "rk_...",
    "status": "ACTIVE",
    "rateLimitPerSec": 100,
    "dailyQuota": 1000000,
    "createdAt": "2026-03-19T01:01:00Z",
    "expiresAt": null
  }
}
```

메모:
- `rawKey`는 발급 시점에만 볼 수 있는 평문 key다.
- DB에는 hash만 저장되므로, 이 값을 잃어버리면 재발급해야 한다.

## 5. 리더보드 생성

`<PROJECT_ID>`는 위 4단계 응답의 `id`로 바꾼다.

```bash
curl -s -X POST http://localhost:8080/leaderboards \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId":"<PROJECT_ID>",
    "name":"manual-board"
  }'
```

기대 결과:
- 응답에 `leaderboardId`가 생성된다.
- 다른 유저의 JWT로는 이 프로젝트에 리더보드를 만들 수 없어야 한다.

## 6. 이벤트 적재

아래 값들을 바꾼다.
- `<RAW_API_KEY>`: 4단계 응답의 `defaultApiKey.rawKey`
- `<LEADERBOARD_ID>`: 5단계 응답의 `id`
- `<USER_ID>`: 2단계 응답의 `id`
- `<IDEMPOTENCY_KEY>`: 매 요청마다 새 UUID

```bash
curl -i -X POST http://localhost:8080/events \
  -H "Authorization: Bearer <RAW_API_KEY>" \
  -H "Idempotency-Key: <IDEMPOTENCY_KEY>" \
  -H 'Content-Type: application/json' \
  -d '{
    "leaderboardId":"<LEADERBOARD_ID>",
    "userId":"<USER_ID>",
    "deltaScore":100
  }'
```

기대 결과:
- `200 OK`
- 응답 헤더에 `X-RateLimit-Remaining`
- 응답 body에 `idempotencyKey`, `replayed`, `processedAt`

같은 유저에 대해 점수를 더 올려보려면 `Idempotency-Key`만 새로 바꿔서 여러 번 호출한다.

## 7. Hot Path 랭킹 조회

### Top ranks

```bash
curl -s "http://localhost:8080/leaderboards/<LEADERBOARD_ID>/tops?offset=0&limit=10"
```

기대 결과:
- Redis 기준 최신 Top N이 반환된다.
- 동점이면 competition ranking(`1,2,2,4`)이 유지된다.

### 단일 유저 조회

```bash
curl -s "http://localhost:8080/leaderboards/<LEADERBOARD_ID>/users/<USER_ID>"
```

기대 결과:
- 참여한 유저면 `score > 0`, `rank != null`
- 미참여 유저면 `score = 0`, `rank = null`

## 8. Snapshot Worker 검증

local profile에서는 worker가 주기적으로 snapshot을 잡는다.
`/events`로 점수를 넣은 뒤 30초 정도 기다리고 아래를 확인한다.

### 내부 상태 API

```bash
curl -s http://localhost:8080/internal/snapshot/status
```

기대 결과:
- `lastSuccessfulSnapshotAt`가 `null`이 아니다.
- `snapshotLagSeconds`가 너무 크게 누적되지 않는다.

### Cold Path snapshot 조회

```bash
curl -s "http://localhost:8080/internal/snapshots/<LEADERBOARD_ID>/entries?offset=0&limit=10"
```

기대 결과:
- 최신 snapshot batch 기준 entries가 반환된다.
- Hot Path와 동일한 `rank, userId, score` 형태로 확인할 수 있다.

## 9. Streams 상태 검증

```bash
curl -s http://localhost:8080/internal/streams/status
```

기대 결과:
- 현재 구현에서는 별도 consumer group이 없으므로 `pendingEntries = 0`
- `consumerLag`는 audit stream 누적 길이로 증가
- `lastDeliveredId`는 마지막으로 기록된 stream entry id

주의:
- PRD의 consumer group lag 계약은 유지하지만, 아직 dedicated consumer가 없어서 lag 계산은 현재 stream length 기반이다.
- 추후 consumer group이 추가되면 이 API는 group lag/pending 기준으로 바뀐다.

## 11. Cold Start Recovery 검증

이 검증은 `docker compose`의 `app` 컨테이너로 실행할 때 가장 단순하다.

1. 먼저 snapshot이 존재해야 한다.
2. Redis hot path key를 비운다.
3. app 컨테이너를 재시작한다.
4. startup runner가 최신 snapshot으로 Redis ZSET을 복구한다.

자동화된 검증:

```bash
LEADERBOARD_ID=<LEADERBOARD_ID> bash scripts/verify-cold-start-recovery.sh
```

기대 결과:
- 앱 재시작 직후 breaker는 recovery 동안 수동 OPEN 상태를 사용한다.
- recovery 완료 후 `/leaderboards/{leaderboardId}/tops`에서 데이터가 다시 보여야 한다.
- 복구된 데이터는 최신 `snapshot_entries`와 동일해야 한다.

## 12. Observability / Grafana

Prometheus + Grafana를 같이 띄우려면:

```bash
docker compose up -d postgres redis app prometheus grafana
```

접속:
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

자세한 계측 항목과 대시보드 설명은 [OBSERVABILITY.md](/Users/bangjaehun/Develop/RealtimeAPI/docs/OBSERVABILITY.md) 참고.

## 10. usage_stats 적재 검증

`POST /events`를 몇 번 호출한 뒤, 발급된 `apiKeyId`로 아래 SQL을 본다.

```sql
select
  api_key_id,
  bucket_type,
  bucket_start,
  request_count,
  allowed_count,
  blocked_count,
  idempotency_hit_count,
  idempotency_miss_count,
  idempotency_conflict_count
from usage_stats
where api_key_id = <API_KEY_ID>
order by bucket_start desc, bucket_type asc;
```

기대 결과:
- 정상 신규 처리: `request_count`, `allowed_count`, `idempotency_miss_count` 증가
- replay: `request_count`, `allowed_count`, `idempotency_hit_count` 증가
- conflict: `request_count`, `allowed_count`, `idempotency_conflict_count` 증가
- rate limit / quota / breaker open: `request_count`, `blocked_count` 증가

`smoke-manual-flow.sh` summary는 이제 `apiKeyId`도 함께 출력한다.

## 11. 실패 케이스 검증

### 관리 API를 JWT 없이 호출

```bash
curl -i -X POST http://localhost:8080/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"unauthorized-project"
  }'
```

기대 결과:
- `401 INVALID_ADMIN_AUTH`

### API key 없이 호출

```bash
curl -i -X POST http://localhost:8080/events \
  -H "Idempotency-Key: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
  -H 'Content-Type: application/json' \
  -d '{
    "leaderboardId":"<LEADERBOARD_ID>",
    "userId":"<USER_ID>",
    "deltaScore":10
  }'
```

기대 결과:
- `401 INVALID_API_KEY`

### 잘못된 API key

```bash
curl -i -X POST http://localhost:8080/events \
  -H "Authorization: Bearer rk_invalid" \
  -H "Idempotency-Key: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
  -H 'Content-Type: application/json' \
  -d '{
    "leaderboardId":"<LEADERBOARD_ID>",
    "userId":"<USER_ID>",
    "deltaScore":10
  }'
```

기대 결과:
- `401 INVALID_API_KEY`

### Circuit Breaker OPEN 수동 검증

아래 스크립트는 Redis를 내려서 `/events`를 반복 호출하고,
`/internal/circuit-breaker/status`가 `OPEN`으로 바뀌는지 확인한다.

```bash
bash scripts/verify-circuit-breaker-open.sh
```

기대 결과:
- 초기 실패 요청이 누적된 뒤 breaker 상태가 `OPEN`
- 이후 `/events`는 `503 CIRCUIT_BREAKER_OPEN`
- 응답 헤더에 `Retry-After: 10`

### Rate limit 초과

기본 key는 초당 100회 제한이다.
짧은 시간에 100회를 넘기면:
- `429 RATE_LIMIT_EXCEEDED`
- `Retry-After`
- `X-RateLimit-Remaining: 0`

## 9. Snapshot 전용 시드가 필요할 때

고정 leaderboard와 tie/empty 시나리오까지 빠르게 검증하려면 아래 스크립트를 사용한다.

```bash
bash scripts/seed-snapshot-demo.sh
```

이 스크립트는:
- demo project / leaderboards / users를 고정 ID로 재생성하고
- Redis에 snapshot 검증용 ZSET을 넣는다.

주의:
- 이 스크립트는 API를 거치지 않고 DB/Redis를 직접 다룬다.
- 따라서 `POST /projects`의 `defaultApiKey` 발급 경로 검증 용도는 아니다.
