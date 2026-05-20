# Benchmark Runbook

적용한 PRD 기준:
- `18. Benchmark & Reliability Test Plan (k6)`
- Tier 0: `T1`, `T3`, `T4`, `T8`
- 핵심 SLO: `Write p99 < 50ms`, `Read p99 < 20ms`, `snapshot_lag_seconds < 30s`, `idempotency 오염 0건`

이 문서는 성능 지표를 일관된 흐름으로 수집하기 위한 상세 실행 런북이다.
[README.md](./README.md)는 테스트 종류 요약용이고, 이 문서는 실제 실행 절차를 다룬다.

## Current Evidence Status

- `T1`: final JSON 3건과 dashboard 캡처 확보
- `T3`: final JSON 1건과 dashboard 캡처 확보
- `T4`: final JSON 1건과 dashboard 캡처 확보
- `T8`: scenario A/B JSON 확보

현재 저장된 대표 수치:

- `T1 / USER_COUNT=300`: `995.67 RPS`, `p99 4.15ms`, `fail 0`
- `T1 / USER_COUNT=500`: `998.67 RPS`, `p99 27.09ms`, `fail 0`
- `T1 / USER_COUNT=1000`: `997.93 RPS`, `p99 20.92ms`, `fail 0`
- `T3 / USER_COUNT=1000`: `write p99 1.47ms`, `read p99 1.65ms`, `fail 0.0032%`
- `T4`: `processed_new_total=1`, `processed_replay_total=49`, `processed_error_total=0`
- `T8 / Scenario A / 30s`: `write p99 1.356ms`, `read p99 1.634ms`, `fail 0.009%`
- `T8 / Scenario B / 5m`: `write p99 1.669ms`, `read p99 1.994ms`, `fail 0.0077%`

산출물 경로는 [../artifacts/README.md](../artifacts/README.md)에서 확인한다.

## Current Conclusion

- `Scenario A (30s)`와 `Scenario B (5m)` 모두 안정성 자체는 양호했다.
- 하지만 `5분 주기`는 freshness를 크게 희생하면서도 write/read p99 이점을 만들지 못했다.
- 현재 결론과 운영 기본값은 `30초 snapshot 주기 유지`가 타당하다.

## 1. 목적

이 런북의 목적은 아래 4가지를 같은 흐름으로 수집하는 것이다.

1. `1,000 TPS` 달성 여부
2. `300 ~ 1000명` 유저 풀에서 랜덤 중복 요청이 발생하는 조건의 재현
3. Grafana/Prometheus 기반 시각자료 확보
4. 테스트 종료 후 생성한 계정/프로젝트/리더보드 정리

## 2. 부하 모델

현재 [lib/bootstrap.js](./lib/bootstrap.js)는 `USER_COUNT`만큼 유저를 미리 생성하고,
각 요청마다 그 유저 풀에서 무작위로 `userId`를 뽑는다.

즉 아래 조건이 이미 만족된다.

- 유저 수는 실행 시 `USER_COUNT`로 조절 가능
- 같은 유저가 여러 번 다시 선택될 수 있음
- 결과적으로 `300 ~ 1000명 사이 유저가 랜덤하게 중복 요청을 보내는` 형태가 됨

권장 실행 세트:

- `USER_COUNT=300`
- `USER_COUNT=500`
- `USER_COUNT=1000`

## 3. 사전 준비

### 3.1 스택 실행

```bash
docker compose up -d postgres redis app prometheus renderer grafana
```

Redis는 named volume을 사용하므로, `docker compose down`만으로는 benchmark key가 남을 수 있다.
`T3/T4` 재측정 전에 완전히 비운 상태를 보장하려면 아래처럼 volume까지 내리는 편이 안전하다.

```bash
docker compose down -v
docker compose up -d --build postgres redis app prometheus renderer grafana
```

로컬에 `k6`가 없으면 아래 래퍼를 사용한다.

```bash
bash scripts/run-k6.sh run k6/t1-hot-path-write.js
```

기본적으로 k6 bootstrap은 벤치마크 전용 API key를 새로 만들고 아래 큰 값을 사용한다.

- `BENCHMARK_RATE_LIMIT_PER_SEC=1000000`
- `BENCHMARK_DAILY_QUOTA=2000000000`

즉 별도 설정을 하지 않으면 benchmark 중 rate limit 영향은 사실상 제거된다.

추가로 compose의 Redis는 benchmark 편의상 `REDIS_MAXMEMORY=${REDIS_MAXMEMORY:-1gb}`를 사용한다.
PRD 기본 가정은 `256MB`지만, 반복 benchmark로 인한 synthetic idempotency key 누적이 측정 자체를 오염시키지 않도록 기본값을 상향했다.
필요하면 아래처럼 다시 줄일 수 있다.

```bash
REDIS_MAXMEMORY=256mb docker compose up -d redis
```

### 3.2 산출물 폴더 생성

```bash
mkdir -p artifacts/k6 artifacts/screenshots
```

### 3.3 이전 테스트 데이터 정리

재실행 전에 아래 스크립트를 돌리면 이전 `k6-project-*`, `k6-admin-*`, `k6-user-*` 데이터와
해당 leaderboard의 Redis 키를 함께 정리할 수 있다.

```bash
bash scripts/cleanup-k6-data.sh
```

이 스크립트는 다음을 자동 처리한다.

- `projects` 삭제
- cascade로 연결된 `leaderboards`, `api_keys`, `usage_stats`, `snapshot_*` 삭제
- Redis `lb:{leaderboardId}:z`, `lb:{leaderboardId}:events`, `lb:{leaderboardId}:idem:*` 삭제

### 3.4 접속 주소

- App: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

Grafana 기본 계정:

- ID: `admin`
- PW: `admin`

Grafana에서 패널/대시보드 PNG export를 쓰려면 `renderer` 서비스가 떠 있어야 한다.

### 3.5 관측 화면 준비

Grafana의 `RealtimeAPI Overview` 대시보드를 열어 둔다.

최소 캡처 대상 패널:

- HTTP RPS
- HTTP p99
- Redis Lua 평균 실행 시간
- Idempotency hit/miss/conflict
- Snapshot lag
- Snapshot duration

## 4. 수집 순서

권장 순서는 아래와 같다.

1. `T1 Fixed`로 대표 수치 확보
2. `T1 Ramp`로 쓰기 처리량 한계선 탐색
3. `T3`로 읽기 SLO 보호 여부 확인
4. `T4`로 멱등성 정합성 증명
5. `T8`로 snapshot 주기 trade-off 수집
6. 결과 표 정리
7. 시각자료 저장
8. 테스트 데이터 정리

## 5. T1 Fixed: Evidence Run

PRD 요구:

- `1,000 TPS`에서 `p99 < 50ms`
- `error rate < 0.1%`

### 5.1 실행

```bash
USER_COUNT=300  DURATION=5m bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-fixed-300.json  k6/t1-fixed-1000-write.js
USER_COUNT=500  DURATION=5m bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-fixed-500.json  k6/t1-fixed-1000-write.js
USER_COUNT=1000 DURATION=5m bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-fixed-1000.json k6/t1-fixed-1000-write.js

bash scripts/report-k6-t1.sh artifacts/k6/t1-fixed-300.json
bash scripts/report-k6-t1.sh artifacts/k6/t1-fixed-500.json
bash scripts/report-k6-t1.sh artifacts/k6/t1-fixed-1000.json
```

### 5.2 기록할 값

- `requestRate`
- `httpFailedRate`
- `durationP95Ms`
- `durationP99Ms` 또는 threshold 통과 여부
- `1000 TPS 고정 부하` 구간에서의 Grafana 패널 캡처

### 5.3 저장할 시각자료

- `HTTP p99` 라인 차트
- `HTTP RPS` 라인 차트
- 필요 시 `Redis Lua duration` 패널

권장 파일명:

- `artifacts/screenshots/t1-http-p99.png`
- `artifacts/screenshots/t1-http-rps.png`
- `artifacts/screenshots/t1-redis-lua.png`

### 5.4 해석 기준

대표 수치는 이 스크립트 결과를 기준으로 쓴다.

- `requestRate`가 `1000`에 근접
- `httpFailedRate < 0.001`
- `p99 < 50ms`

즉 램프 평균값이 아니라, `1000 TPS 고정 부하에서 SLO를 만족했는가`를 바로 보여주는 결과가 된다.

## 6. T1 Ramp: Capacity Exploration

램프 스크립트는 대표 수치보다는 한계선 탐색용이다.

```bash
USER_COUNT=300  bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-ramp-300.json  k6/t1-hot-path-write.js
USER_COUNT=500  bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-ramp-500.json  k6/t1-hot-path-write.js
USER_COUNT=1000 bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-ramp-1000.json k6/t1-hot-path-write.js
```

이 결과는 `100 -> 500 -> 1000 -> 1500 TPS` 구간 변화 추이를 보여주는 보조 자료로 쓴다.

## 7. T3: Mixed Workload

PRD 요구:

- 기본 시나리오 `Write 80% + Read 20%`
- 성공 기준 `Read p99 < 20ms`

### 7.1 실행

```bash
USER_COUNT=300  WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t3-300.json  k6/t3-mixed-workload.js
USER_COUNT=1000 WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t3-1000.json k6/t3-mixed-workload.js
USER_COUNT=1000 WRITE_RPS=950 READ_RPS=50  DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t3-950-50.json k6/t3-mixed-workload.js
```

Redis OOM이 한 번이라도 있었던 뒤 재측정할 때는 아래 순서를 권장한다.

```bash
docker compose down -v
docker compose up -d --build postgres redis app prometheus renderer grafana

USER_COUNT=1000 WRITE_RPS=800 READ_RPS=200 DURATION=10m \
bash scripts/run-k6.sh run \
  --summary-export artifacts/k6/final/t3/t3-mixed-1000-clean.json \
  k6/t3-mixed-workload.js
```

### 7.2 기록할 값

- Write `p99`
- Read `p99`
- `http_req_failed`
- 읽기 API 응답 안정성

### 7.3 저장할 시각자료

- `Write p99` 패널
- `Read p99` 패널
- `Idempotency hit/miss/conflict` 추이

권장 파일명:

- `artifacts/screenshots/t3-write-p99.png`
- `artifacts/screenshots/t3-read-p99.png`
- `artifacts/screenshots/t3-idempotency.png`

## 8. T4: Idempotency Correctness

PRD 요구:

- 동일 `Idempotency-Key`로 동시 `50`개 POST
- 신규 처리 `1건`
- replay `49건`
- 점수 오염 `0건`

### 8.1 실행

```bash
bash scripts/run-k6.sh run --summary-export artifacts/k6/t4.json k6/t4-idempotency-correctness.js
```

### 8.2 확인 포인트

- `processed_new_total == 1`
- `processed_replay_total == 49`
- `processed_error_total == 0`
- 대상 유저의 실제 점수가 기대값과 일치하는지 확인

### 8.3 저장할 시각자료

- k6 summary 결과
- Grafana의 `idempotency_hit_total`, `idempotency_miss_total`, `idempotency_conflict_total`

권장 파일명:

- `artifacts/screenshots/t4-idempotency-metrics.png`

## 9. T8: Snapshot Pipeline Impact

PRD 요구:

- 시나리오 A: snapshot `30초`
- 시나리오 B: snapshot `5분`
- 비교 지표: `snapshot_lag_seconds`, `Write p99`

### 9.1 시나리오 A 실행

```bash
USER_COUNT=300  WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t8-30s-300.json  k6/t8-snapshot-impact.js
USER_COUNT=1000 WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t8-30s-1000.json k6/t8-snapshot-impact.js
```

### 9.2 시나리오 B 실행

앱 설정을 아래처럼 바꾸고 재기동 후 같은 테스트를 반복한다.

```properties
snapshots.worker.delay-ms=300000
```

```bash
USER_COUNT=300  WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t8-5m-300.json  k6/t8-snapshot-impact.js
USER_COUNT=1000 WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run --summary-export artifacts/k6/t8-5m-1000.json k6/t8-snapshot-impact.js
```

### 9.3 기록할 값

- `snapshot_lag_seconds`
- `snapshot_duration_seconds`
- snapshot 실행 중 `Write p99`
- snapshot 미실행 구간 대비 증가율

### 9.4 저장할 시각자료

- `Snapshot lag` 비교 캡처
- `Snapshot duration` 캡처
- `Write p99` 비교 캡처

권장 파일명:

- `artifacts/screenshots/t8-snapshot-lag-30s.png`
- `artifacts/screenshots/t8-snapshot-lag-5m.png`
- `artifacts/screenshots/t8-write-p99-compare.png`

## 10. 결과 표 정리 방식

결과 표는 `목표`와 `실측값`을 분리해서 적는다.

```markdown
### T1: Hot Path Write Throughput

| User Pool | Target TPS | p50 | p95 | p99 | Error Rate | SLO |
| --- | --- | --- | --- | --- | --- | --- |
| 300 | 1000 | X | X | X | X | PASS/FAIL |
| 500 | 1000 | X | X | X | X | PASS/FAIL |
| 1000 | 1000 | X | X | X | X | PASS/FAIL |
```

```markdown
### T3: Mixed Workload

| User Pool | Write RPS | Read RPS | Write p99 | Read p99 | Error Rate | SLO |
| --- | --- | --- | --- | --- | --- | --- |
| 300 | 800 | 200 | X | X | X | PASS/FAIL |
| 1000 | 800 | 200 | X | X | X | PASS/FAIL |
```

```markdown
### T8: Snapshot Trade-off

| Snapshot Delay | User Pool | Write p99 | Snapshot Lag | 판단 |
| --- | --- | --- | --- | --- |
| 30s | 1000 | 1.356ms | 15~30s | Prefer |
| 5m | 1000 | 1.669ms | up to 5m | Higher staleness |
```

기록 원칙:

- 달성하지 못한 수치는 `목표`로만 적는다.
- 측정 완료한 수치만 `검증 결과`로 적는다.
- 절대값보다 `어떤 조건에서 어떤 SLO를 충족했는지`를 함께 적는다.

## 11. 테스트 종료 후 데이터 정리

벤치마크 종료 후에는 아래 스크립트 한 줄만 실행하면 된다.

```bash
bash scripts/cleanup-k6-data.sh
```

이 스크립트는 재실행 전 사전 정리용으로도 그대로 쓸 수 있다.

## 12. 산출물 체크리스트

한 번의 benchmark 사이클이 끝나면 아래 3종이 남아 있어야 한다.

1. `artifacts/k6/*.json`
2. `artifacts/screenshots/*.png`
3. 결과 요약 표 1개 이상
