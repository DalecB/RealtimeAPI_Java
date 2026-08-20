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
