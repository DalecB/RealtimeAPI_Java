# Realtime Ranking & Event Processing API

Redis Hot Path와 PostgreSQL Cold Path를 분리해 실시간 랭킹 이벤트를 처리하는 Spring Boot 백엔드입니다.

적용한 PRD 기준:
- [docs/PRD.md](docs/PRD.md)
- 목표 처리량: `Write 1,000 TPS`
- 핵심 SLO: `POST /events p99 < 50ms`, `Read p99 < 20ms`, `snapshot_lag_seconds < 30s`, `idempotency 오염 0건`

## What This Project Shows

- Redis Lua로 `Idempotency + ZINCRBY + XADD`를 원자적으로 처리
- Redis를 Source of Truth로 쓰고 PostgreSQL snapshot으로 복구 가능성 확보
- Circuit Breaker, Prometheus, Grafana를 포함한 운영 관측성
- k6 부하 테스트와 산출물로 SLO 달성 여부를 수치로 설명

## Architecture

- Hot Path: `POST /events` -> Redis Lua -> ZSET / idempotency key / audit stream
- Cold Path: Snapshot worker (`30s` 기본 주기) -> Redis Top-N 조회 -> PostgreSQL upsert
- Recovery: Redis cold start 시 PostgreSQL 최신 snapshot으로 복구

상세 설계와 ADR은 [docs/PRD.md](docs/PRD.md)에 정리되어 있습니다.

## Current Implementation Notes

- Snapshot worker 기본 주기는 `30초`입니다. T8 비교를 위해 `5분` 주기로도 재기동해 측정했습니다.
- 관리용 JWT 로그인은 현재 범위에서 `users.externalId` 기반 demo auth를 사용합니다.
- `/internal/streams/status`의 `pendingEntries`는 아직 dedicated consumer group이 없어 항상 `0`이고, `streamLength`는 audit stream의 길이(XLEN)입니다.

## Quick Start

전체 로컬 스택:

```bash
docker compose up -d postgres redis app prometheus renderer grafana
```

접속 주소:

- App: `http://localhost:8080`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

핵심 문서:

- 제품/아키텍처 명세: [docs/PRD.md](docs/PRD.md)
- 수동 검증 플로우: [docs/MANUAL_VERIFICATION.md](docs/MANUAL_VERIFICATION.md)
- 관측성 가이드: [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md)
- k6 실행 가이드: [k6/README.md](k6/README.md)
- benchmark 실행 런북: [k6/BENCHMARK_RUNBOOK.md](k6/BENCHMARK_RUNBOOK.md)
- 산출물 인덱스: [artifacts/README.md](artifacts/README.md)

## Verification Status

테스트:

- `./gradlew test`
- 단위 테스트(WebMvc 슬라이스, mock 기반)와 Testcontainers 통합 테스트(실제 Redis·PostgreSQL 컨테이너)로 구성됩니다.

Testcontainers 통합 테스트:

| Scenario | 검증 내용 | Test |
| --- | --- | --- |
| 동시 멱등 처리 | 동일 Idempotency Key·동일 payload 50건 동시 요청(k6 T4의 JVM판). Lua 원자 블록이 check-and-act를 한 단위로 묶어 신규 1건/replay 49건, 점수 반영 정확히 1회(ZSCORE), audit 기록 정확히 1건(XLEN)을 보장하는지 검증 | `ProcessEventConcurrencyTest` |
| 멱등키 재사용 충돌 | 동일 키에 payload 해시(`userId:delta`)가 다르면 replay가 아닌 데이터 변주로 간주하고 `IdempotencyKeyReuseMismatchException`을 던지며 점수가 반영되지 않는지 검증 (HTTP 409 매핑은 `EventCommandControllerWebMvcTest` 몫) | `ProcessEventConcurrencyTest` |
| Redis 장애 fail-fast | 성공 10건으로 sliding window를 채운 상태에서 Redis 컨테이너를 중단 — 임계(50%) 도달인 5번째 실패에서 브레이커가 OPEN 되고, 이후 요청은 커넥션 타임아웃을 기다리지 않고 즉시 실패하는지 검증 | `RedisOutageCircuitBreakerTest` |
| Snapshot 왕복 복구 | Redis ZSET 3건을 PostgreSQL snapshot으로 capture → 랭킹 키 삭제 → recover. `recovered=true` / `RECOVERED` / 복구 행 수 3을 먼저 확정해 skip(`REDIS_ALREADY_WARM`)에 의한 거짓 통과를 배제하고, 유저별 ZSCORE와 ZCARD가 원본과 일치하는지 검증 | `SnapshotRoundTripTest` |

수집된 benchmark evidence:

| Test | Current Evidence | Artifact |
| --- | --- | --- |
| T1 Fixed | `300 users: p99 4.15ms / 995.67 RPS / fail 0` | `artifacts/k6/final/t1/t1-fixed-300-clean.json` |
| T1 Fixed | `500 users: p99 27.09ms / 998.67 RPS / fail 0` | `artifacts/k6/final/t1/t1-fixed-500.json` |
| T1 Fixed | `1000 users: p99 20.92ms / 997.93 RPS / fail 0` | `artifacts/k6/final/t1/t1-fixed-1000.json` |
| T3 Mixed | `write p99 1.47ms / read p99 1.65ms / fail 0.0032%` | `artifacts/k6/final/t3/t3-mixed-1000-clean.json` |
| T4 Idempotency | `new 1 / replay 49 / error 0` | `artifacts/k6/final/t4/t4-idempotency-clean.json` |
| T8 Scenario A | `30s snapshot / write p99 1.36ms / read p99 1.63ms / fail 0.009%` | `artifacts/k6/final/t8/t8-30s-1000.json` |
| T8 Scenario B | `5m snapshot / write p99 1.67ms / read p99 1.99ms / fail 0.0077%` | `artifacts/k6/final/t8/t8-5m-1000.json` |

메모:

- PRD benchmark 기준 환경은 Redis `256MB`입니다.
- 현재 compose 기본값은 benchmark 재현 편의를 위해 `REDIS_MAXMEMORY=1gb`입니다.
- `256MB` 기준 재검증은 `REDIS_MAXMEMORY=256mb docker compose up ...`로 수행할 수 있습니다.

## Main APIs

- `POST /events`
- `GET /leaderboards/{leaderboardId}/tops`
- `GET /leaderboards/{leaderboardId}/users/{userId}`
- `POST /users`
- `POST /auth/login`
- `POST /projects`
- `POST /leaderboards`
- `POST /admin/api-keys`
- `GET /internal/snapshot/status`
- `GET /internal/streams/status`
- `GET /internal/circuit-breaker/status`

실제 요청 예시는 [docs/MANUAL_VERIFICATION.md](docs/MANUAL_VERIFICATION.md)에 있습니다.

## Design Notes

- 기능의 개수보다 운영 리스크(idempotency, hot key, snapshot lag, fail-fast)를 어떻게 다뤘는지에 초점을 둔 프로젝트입니다.
- Strong Consistency 대신 Eventually Consistent 모델을 명시적으로 채택했습니다.
- T8 비교 결과 `30초 snapshot 주기`가 기본 운영값으로 더 적절했습니다. `5분 주기`는 snapshot lag가 최대 5분까지 증가했지만 write/read p99 개선으로 이어지지 않았습니다.
