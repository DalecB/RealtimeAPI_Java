# Benchmark Artifacts

PRD 기준:
- `T1`: `1,000 TPS`에서 `p99 < 50ms`, `error rate < 0.1%`
- `T3`: Write 부하 중 `Read p99 < 20ms`
- `T4`: 멱등성 오염 `0건`

## Structure

- `k6/final/t1`
  - 최종 채택한 `T1` JSON
- `k6/final/t3`
  - 최종 채택한 `T3` JSON
- `k6/final/t4`
  - 최종 채택한 `T4` JSON
- `k6/pending`
  - 다시 측정이 필요한 JSON
- `k6/rejected`
  - 오염되었거나 폐기한 JSON
- `screenshots/final/t1`
  - 최종 채택한 `T1` 대시보드 캡처
- `screenshots/final/t3`
  - 최종 채택한 `T3` 캡처
- `screenshots/final/t4`
  - 최종 채택한 `T4` 캡처
- `screenshots/rejected/t1`
  - 초기 실패/오염 run 캡처

## Current Final Files

- `k6/final/t1/t1-fixed-300-clean.json`
- `k6/final/t1/t1-fixed-500.json`
- `k6/final/t1/t1-fixed-1000.json`
- `k6/final/t1/t1-kafka-e2e-256mb.json`
- `k6/final/t1/t1-kafka-e2e-256mb-27b5b47.json`
- `k6/final/t3/t3-mixed-1000-clean.json`
- `k6/final/t4/t4-idempotency-clean.json`
- `k6/final/t8/t8-30s-1000.json`
- `k6/final/t8/t8-5m-1000.json`
- `screenshots/final/t1/t1-fixed-300-clean-dashboard.png`
- `screenshots/final/t1/t1-fixed-500-dashboard-1.png`
- `screenshots/final/t1/t1-fixed-500-dashboard-2.png`
- `screenshots/final/t1/t1-fixed-500-dashboard-3.png`
- `screenshots/final/t1/t1-fixed-1000-dashboard-1.png`
- `screenshots/final/t1/t1-fixed-1000-dashboard-2.png`
- `screenshots/final/t1/t1-fixed-1000-dashboard-3.png`
- `screenshots/final/t1/t1-kafka-e2e-256mb.png`
- `screenshots/final/t3/t3-mixed-1000-clean.png`
- `screenshots/final/t4/t4-idempotency-clean.png`

## Current Status

- `T1`: 수집 완료(Redis 256MB Kafka E2E 포함)
- `T3`: 수집 완료
- `T4`: 수집 완료
- `T8`: JSON 수집 완료

## T1 Fixed 1000 Summary

- 부하: `USER_COUNT=1000`, `WRITE_RPS=1000`, `DURATION=5m`
- 결과: `997.93 RPS`, `p99 20.92ms`, `max 232.33ms`, 오류율 `0%`
- 판정: `p99 < 50ms` threshold 통과
- 해석 제한: `max 232.33ms`는 단일 최대 지연이다. summary JSON에는 해당 요청의 시점과 실행 단계가 없으므로 콜드 스타트나 특정 원인으로 해석하지 않는다.

## Kafka E2E Summary

- 환경: Redis `256MB`, `noeviction`, AOF `appendfsync everysec`, Kafka 단일 브로커
- 부하: `USER_COUNT=1000`, `WRITE_RPS=1000`, `DURATION=5m`
- HTTP: `990.44 RPS`, `p99 3.99ms`, 오류율 `0%`, 중단·누락 iteration `0건`
- 전달 결과: Kafka `300,001건`, PostgreSQL `300,001건`
- 종료 상태: Redis 미수신 `0건`, Redis 미확인 `0건`, Kafka 미처리 `0건`
- 판정: `PASS`

### Phase 3 이후 재측정 (2026-11-16, `27b5b47`)

- 조건: 위와 같다. 기존 로컬 데이터와 섞이지 않도록 별도 compose 프로젝트(`-p realtimeapi-bench`)의 새 volume에서 실행했다.
- HTTP: `991.11 RPS`, `p99 11.82ms`, `max 407.14ms`, 오류율 `0%`, `300,001` iteration 전부 완료
- 전달 결과: Kafka `300,001건`, PostgreSQL `300,001건`, DLT `0건`
- 종료 상태: Redis 미확인 `0건`, Redis 미수신 `0건`, Kafka 미처리 `0건`
- 판정: `PASS`. p99는 Phase 2 측정(`3.99ms`)보다 높지만 기준 `50ms` 안이다. 두 실행의 차이 원인은 분석하지 않았다.
- `rejected/t1-current-e2e-20260907-not-reproduced.json`: 2026-09-07 실행은 `p99 62.23ms`로 기준을 넘었지만 현재 main에서 재현되지 않았다. 실행 조건 기록이 없어 원인을 특정하지 못했으므로 증거에서 제외한다.

## Redis 멱등 키 용량 (2026-11-16 실측)

위 재측정 직후 Redis 256MB 인스턴스에서 측정했다.

- 멱등 키 수: `300,001`개 (`lb:{leaderboardId}:idem:<key>`, TTL 24시간)
- 키 1개: `MEMORY USAGE` 표본 500개 평균 `232B`. 키 인덱스·만료 테이블까지 포함한 실효값은 `285B`다. (`used_memory` − 시작 시 메모리 − 스트림 · ZSET) ÷ 키 수로 계산했다.
- 계산 (실측 아님):
  - `256MB`에는 멱등 키가 약 `94만`개 들어간다. 1,000 TPS가 계속되면 약 `16분` 만에 가득 찬다. 이후에는 `noeviction`이라 Lua의 SET이 OOM으로 거부되고 쓰기가 실패한다.
  - 1,000 TPS를 TTL 24시간 동안 유지하려면 멱등 키만 `8,640만`개 × `285B` ≈ `24.6GB`가 필요하다.
  - `256MB`로 버틸 수 있는 하루 평균 처리량은 약 `11 TPS`다.
- 5분 벤치마크는 이 한계에 닿지 않는다. 30분 이상 soak(T9)를 공식 조건으로 돌리면 기준을 넘길 것으로 예상된다.

## T8 Summary

- `30s snapshot`
  - `write p99 1.356ms`
  - `read p99 1.634ms`
  - `fail 0.009%`
- `5m snapshot`
  - `write p99 1.669ms`
  - `read p99 1.994ms`
  - `fail 0.0077%`
- 결론
  - `5분 주기`는 최신성을 크게 낮추지만 지연 시간을 개선하지 못했다.
  - 운영 기본값은 `30초 주기`가 더 타당하다.
