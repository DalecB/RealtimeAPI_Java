# Observability

적용한 PRD 기준:
- `15. Observability`
- `10.2 Circuit Breaker 설정`
- `8.4/9. Cold Start Recovery`

## 1. 로컬 스택 실행

```bash
docker compose up -d postgres redis app prometheus grafana
```

접속 주소:
- App: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `admin`)
- Prometheus scrape endpoint: `http://localhost:8080/actuator/prometheus`

## 2. 현재 계측된 핵심 메트릭

### HTTP Layer
- `http_server_requests_seconds`
- `http_server_requests_seconds_count`

### Hot Path
- `redis_lua_duration_ms{script="process_event|check_api_key_limits"}`
- `idempotency_hit_total`
- `idempotency_miss_total`
- `idempotency_conflict_total`
- `rate_limit_block_total{apiKeyId="..."}`

### Circuit Breaker
- `circuit_breaker_state{state="closed|half_open|open"}`
- `circuit_breaker_failure_rate`

### Cold Path
- `snapshot_duration_seconds`
- `snapshot_lag_seconds`
- `snapshot_failure_total`
- `snapshot_skip_total`
- `cold_start_recovery_total`

### Streams
- `stream_pending_entries`
- `stream_consumer_lag`

## 3. Grafana 대시보드

기본 대시보드는 compose 시작 시 자동 프로비저닝됩니다.

포함 패널:
- HTTP RPS / HTTP p99
- Redis Lua 평균 실행 시간
- Idempotency hit/miss/conflict 추이
- Rate limit block 추이
- Circuit breaker failure rate / state
- Snapshot lag / duration / counters
- Streams pending / lag

## 4. 추천 확인 순서

1. `bash scripts/smoke-manual-flow.sh`
2. Grafana에서 `RealtimeAPI Overview` 열기
3. `/events`를 반복 호출하면서 다음을 확인
   - `idempotency_miss_total` 증가
   - `redis_lua_duration_ms` 변화
   - `http_server_requests_seconds` p99
4. `bash scripts/verify-circuit-breaker-open.sh`
5. Grafana에서 다음을 확인
   - `circuit_breaker_state{state="open"}`가 1로 올라가는지
   - `circuit_breaker_failure_rate` 증가
   - `rate_limit_block_total`과 구분되는지
6. `LEADERBOARD_ID=your-leaderboard-id bash scripts/verify-cold-start-recovery.sh`
7. Grafana에서 다음을 확인
   - `cold_start_recovery_total` 증가
   - `snapshot_lag_seconds`가 계속 관측되는지

## 5. 주의할 점

- `stream_pending_entries`, `stream_consumer_lag`는 현재 dedicated consumer group이 없어서 임시 정의입니다.
  - `pendingEntries = 0`
  - `consumerLag = audit stream length`
- `snapshot status`는 메모리 tracker 기반이라 앱 재시작 시 마지막 성공 시각은 초기화됩니다.
- recovery 검증 스크립트는 compose의 `app` 컨테이너를 재시작하는 방식이라, `bootRun` 로컬 프로세스가 아니라 compose app 기준으로 사용해야 합니다.
