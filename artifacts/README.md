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
- `screenshots/final/t3/t3-mixed-1000-clean.png`
- `screenshots/final/t4/t4-idempotency-clean.png`

## Current Status

- `T1`: collected
- `T3`: collected
- `T4`: collected
- `T8`: collected (JSON)

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
  - `5분 주기`는 freshness를 크게 희생하지만 latency 이점을 주지 못했다.
  - 운영 기본값은 `30초 주기`가 더 타당하다.
