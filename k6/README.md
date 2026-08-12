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
REDIS_MAXMEMORY=256mb docker compose up -d --build postgres redis kafka app prometheus renderer grafana
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
- `REDIS_MAXMEMORY` 개발 기본값: `1gb` (`docker-compose.yml`)

주의:
- 공식 벤치마크 환경은 PRD 기준인 `Redis maxmemory 256MB`다.
- 최종 측정 명령에는 `REDIS_MAXMEMORY=256mb`를 명시한다. 1GB 기본값은 반복 개발 실행용이며 공식 결과 환경이 아니다.

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

## 현재 Kafka 파이프라인 E2E 검증

기존 T1 결과는 HTTP SLO를 판정하고, Ops Console은 같은 부하가 `Redis Stream → Kafka → PostgreSQL`까지 처리되는지 확인합니다. 콘솔은 부하를 발생시키지 않습니다.

1. 전체 스택을 실행합니다.

```bash
REDIS_MAXMEMORY=256mb docker compose up -d --build postgres redis kafka app prometheus renderer grafana
```

2. Kafka 컨슈머를 2개 인스턴스로 검증하려면 같은 이미지로 컨슈머 전용 인스턴스를 하나 더 실행합니다. 릴레이·스냅샷·콜드 스타트 복구는 app-1에서만 실행합니다.

```bash
docker compose run -d --rm \
  --name realtime-api-consumer \
  --no-deps \
  -e EVENTS_RELAY_ENABLED=false \
  -e SNAPSHOTS_WORKER_ENABLED=false \
  -e SNAPSHOTS_RECOVERY_ENABLED=false \
  app
```

3. [http://localhost:8080/console/](http://localhost:8080/console/)에서 `k6 E2E Observer`의 `Start baseline`을 누릅니다. Redis와 Kafka 미처리 건수가 0일 때만 시작할 수 있습니다.

4. 별도 터미널에서 현재 코드 기준 1,000 TPS 테스트를 실행합니다.

```bash
USER_COUNT=1000 WRITE_RPS=1000 DURATION=5m \
bash scripts/run-k6.sh run \
  --summary-export artifacts/k6/pending/t1-current-e2e.json \
  k6/t1-fixed-1000-write.js
```

5. 결과는 두 곳에서 나눠 확인합니다.

- k6: 약 1,000 RPS, p99 `< 50ms`, 오류율 `< 0.1%`
- Ops Console: `Redis unread(릴레이 미수신)=0`, `Redis unacked(릴레이 미확인)=0`, `Kafka unprocessed(컨슈머 미처리)=0`
- Ops Console: `Kafka received`와 `PostgreSQL stored` 증가량 일치
- 콘솔의 `caught up(적체 해소)`은 메시지 수가 일치하고 모든 미처리 건수가 0일 때 표시됩니다.

6. 추가 컨슈머를 종료합니다. `--rm`으로 실행했으므로 종료 후 컨테이너도 제거됩니다.

```bash
docker stop realtime-api-consumer
```

이 검증은 현재 로컬 단일 브로커 구성의 처리 결과입니다. 프로덕션 Kafka 복제 구성이나 API 요청의 멀티 인스턴스 분산까지 검증한 것은 아닙니다.

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
docker compose up -d --build postgres redis kafka app prometheus renderer grafana

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
