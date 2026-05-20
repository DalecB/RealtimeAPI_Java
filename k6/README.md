# k6 Benchmark

적용한 PRD 기준:
- `18. Benchmark & Reliability Test Plan (k6)`
- Tier 0: `T1`, `T3`, `T4`, `T8`

상세 실행 런북:
- 지표 수집 전체 플로우는 [BENCHMARK_RUNBOOK.md](./BENCHMARK_RUNBOOK.md) 참고

## 전제

- 앱과 인프라가 떠 있어야 합니다.
- 권장: compose 전체 실행
- 로컬에 `k6`가 없으면 `bash scripts/run-k6.sh ...`를 사용하면 됩니다.
- 이전 k6 테스트 데이터를 비우려면 `bash scripts/cleanup-k6-data.sh`를 먼저 실행하면 됩니다.
- Grafana에서 패널/대시보드 PNG export를 쓰려면 `renderer` 서비스도 함께 떠 있어야 합니다.
- Redis는 named volume을 쓰므로, `docker compose down`만으로는 benchmark 데이터가 남을 수 있습니다.
- `T3/T4`를 다시 깨끗하게 측정할 때는 `docker compose down -v`로 Redis volume까지 비우는 편이 안전합니다.

```bash
docker compose up -d postgres redis app prometheus renderer grafana
```

```bash
bash scripts/cleanup-k6-data.sh
```

## 공통 환경변수

- `BASE_URL` 기본값: `http://localhost:8080`
- `USER_COUNT` 기본값: `200`
- `DELTA_SCORE` 기본값: `1`
- `WRITE_RPS` 기본값: `1000` (`t1-fixed-1000-write.js`에서 사용)
- `DURATION` 기본값: `5m` (`t1-fixed-1000-write.js`에서 사용)
- `BOOTSTRAP_READY_TIMEOUT_MS` 기본값: `60000`
- `BOOTSTRAP_READY_INTERVAL_MS` 기본값: `1000`
- `BENCHMARK_RATE_LIMIT_PER_SEC` 기본값: `1000000`
- `BENCHMARK_DAILY_QUOTA` 기본값: `2000000000`
- `REDIS_MAXMEMORY` 기본값: `1gb` (`docker-compose.yml`에서 benchmark 편의상 상향)

주의:
- PRD의 benchmark 환경 가정은 `Redis maxmemory 256MB`이지만, 현재 재측정은 clean evidence 확보가 우선이라 compose 기본값을 `1gb`로 상향했다.
- `256MB` 조건을 다시 검증하려면 `REDIS_MAXMEMORY=256mb docker compose up ...`로 명시하면 된다.

기본 동작:

- k6 bootstrap은 프로젝트 생성 후 `defaultApiKey`를 그대로 쓰지 않는다.
- 대신 벤치마크 전용 API key를 추가 발급하고, 그 키에 매우 큰 rate limit/quota를 설정해 성능 측정 중 rate limit 영향이 사실상 없도록 한다.

## T1 Fixed: Evidence Run

PRD 요구:
- 성공 기준: `1,000 TPS`에서 `p99 < 50ms`, `error rate < 0.1%`

```bash
USER_COUNT=300 DURATION=5m bash scripts/run-k6.sh run --summary-export artifacts/k6/t1-fixed-300.json k6/t1-fixed-1000-write.js
bash scripts/report-k6-t1.sh artifacts/k6/t1-fixed-300.json
```

## T1 Ramp: Hot Path Write Throughput

PRD 요구:
- 100 -> 500 -> 1000 -> 1500 TPS 단계 상승
- 성공 기준: `p99 < 50ms`, `error rate < 0.1%`

```bash
bash scripts/run-k6.sh run k6/t1-hot-path-write.js
```

## T3: Mixed Workload

PRD 요구:
- 기본 시나리오: Write 80% / Read 20%
- 성공 기준: Read `p99 < 20ms`

```bash
WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run k6/t3-mixed-workload.js
```

권장 재측정 순서:

```bash
docker compose down -v
docker compose up -d --build postgres redis app prometheus renderer grafana

USER_COUNT=1000 WRITE_RPS=800 READ_RPS=200 DURATION=10m \
bash scripts/run-k6.sh run \
  --summary-export artifacts/k6/final/t3/t3-mixed-1000-clean.json \
  k6/t3-mixed-workload.js
```

추가 비교:

```bash
WRITE_RPS=950 READ_RPS=50 DURATION=10m bash scripts/run-k6.sh run k6/t3-mixed-workload.js
```

## T4: Idempotency Correctness

PRD 요구:
- 동일 Idempotency-Key로 동시 50개 POST
- 성공 기준:
  - 신규 처리 1건
  - replay 49건
  - 점수 오염 0건

```bash
bash scripts/run-k6.sh run k6/t4-idempotency-correctness.js
```

## T8: Snapshot Pipeline Impact

PRD 요구:
- 시나리오 A: snapshot 30초
- 시나리오 B: snapshot 5분
- 비교 지표:
  - `snapshot_lag_seconds`
  - Write p99

### 시나리오 A (30초)

```bash
WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run k6/t8-snapshot-impact.js
```

### 시나리오 B (5분)

앱 설정을 아래처럼 바꿔 재기동 후 같은 스크립트를 반복합니다.

```properties
snapshots.worker.delay-ms=300000
```

```bash
WRITE_RPS=800 READ_RPS=200 DURATION=10m bash scripts/run-k6.sh run k6/t8-snapshot-impact.js
```

## 결과 기록 포인트

README나 별도 리포트에 최소한 아래를 남기는 게 좋습니다.

1. 테스트 환경
- 머신 스펙
- Docker Compose 여부
- JVM heap / Redis maxmemory

2. 결과 표
- p50 / p95 / p99 / error rate

3. 관측 스크린샷
- Grafana dashboard
- Prometheus query

4. 해석
- SLO 충족 여부
- 병목 구간
- trade-off 설명
