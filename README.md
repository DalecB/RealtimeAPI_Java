# Realtime Ranking & Event Processing API

Redis 핫패스와 PostgreSQL 콜드패스를 분리하고, 감사 이벤트를 Redis Streams 아웃박스에서 Kafka를 거쳐 PostgreSQL에 적재하는 Spring Boot 백엔드입니다.

적용한 PRD 기준:
- [docs/PRD.md](docs/PRD.md)
- 목표 처리량: `Write 1,000 TPS`
- 핵심 SLO: `POST /events p99 < 50ms`, `Read p99 < 20ms`, `snapshot_lag_seconds < 30s`, `idempotency 오염 0건`

## What This Project Shows

- Redis Lua로 `Idempotency + ZINCRBY + XADD`를 원자적으로 처리
- Redis를 Source of Truth로 쓰고 PostgreSQL snapshot으로 복구 가능성 확보
- Redis Streams 아웃박스 → Kafka → PostgreSQL 감사 로그 파이프라인
- Circuit Breaker, Prometheus, Grafana를 포함한 운영 관측성
- k6 부하 테스트와 산출물로 SLO 달성 여부를 수치로 설명

## Architecture

- Hot Path: `POST /events` -> Redis Lua -> ZSET / idempotency key / audit stream
- Audit Path: Redis Stream -> relay -> Kafka `lb-audit-events` -> consumer -> PostgreSQL `audit_events`
- Cold Path: Snapshot worker (`30s` 기본 주기) -> Redis Top-N 조회 -> PostgreSQL upsert
- Recovery: Redis cold start 시 PostgreSQL 최신 snapshot으로 복구

상세 설계와 ADR은 [docs/PRD.md](docs/PRD.md)에 정리되어 있습니다.

## 현재 구현 범위

- Phase 2는 **단일 릴레이 + 고정 컨슈머 이름**, Kafka 단일 브로커, PostgreSQL 멱등 저장, Kafka 컨슈머 재시작·2인스턴스 재할당 검증까지 완료했습니다.
- Snapshot worker 기본 주기는 `30초`입니다. T8 비교를 위해 `5분` 주기로도 재기동해 측정했습니다.
- 관리용 JWT 로그인은 현재 범위에서 `users.externalId` 기반 demo auth를 사용합니다.
- `/internal/streams/status`의 `consumerGroupLag`는 릴레이가 아직 읽지 않은 건수, `pendingEntries`는 읽었지만 XACK하지 않은 건수(XPENDING)입니다. `streamLength`는 이미 처리된 항목까지 포함한 감사 스트림의 전체 길이(XLEN)입니다.

## Quick Start

전체 로컬 스택:

```bash
docker compose up -d postgres redis kafka app prometheus renderer grafana
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

## 검증 현황

테스트:

- `./gradlew test`
- 단위 테스트(WebMvc 슬라이스, mock 기반)와 Testcontainers 통합 테스트(실제 Redis·Kafka·PostgreSQL 컨테이너)로 구성됩니다.

Testcontainers 통합 테스트:

| 시나리오 | 검증 내용 | 테스트 |
| --- | --- | --- |
| 동시 멱등 처리 | 동일 Idempotency Key·동일 payload로 50건을 동시에 요청한다. Lua가 check-and-act를 한 번에 처리해 신규 1건/replay 49건, 점수 반영 1회(ZSCORE), audit 기록 1건(XLEN)을 보장하는지 검증 | `ProcessEventConcurrencyTest` |
| 멱등키 재사용 충돌 | 동일 키의 payload 해시(`userId:delta`)가 다르면 replay가 아닌 요청 내용 변경으로 판단한다. `IdempotencyKeyReuseMismatchException`을 던지고 점수를 반영하지 않는지 검증하며, HTTP 409 매핑은 `EventCommandControllerWebMvcTest`에서 확인 | `ProcessEventConcurrencyTest` |
| Redis 장애 fail-fast | 성공 10건으로 sliding window를 채운 상태에서 Redis 컨테이너를 중단 — 임계(50%) 도달인 5번째 실패에서 브레이커가 OPEN 되고, 이후 요청은 커넥션 타임아웃을 기다리지 않고 즉시 실패하는지 검증 | `RedisOutageCircuitBreakerTest` |
| Snapshot 왕복 복구 | Redis ZSET 3건을 PostgreSQL snapshot으로 저장한 뒤 랭킹 키를 삭제하고 복구한다. `recovered=true` / `RECOVERED` / 복구 행 수 3을 먼저 확인해 `REDIS_ALREADY_WARM`으로 건너뛴 경우를 제외하고, 유저별 ZSCORE와 ZCARD가 원본과 일치하는지 검증 | `SnapshotRoundTripTest` |
| Kafka 감사 로그 왕복 | 이벤트 처리의 Lua XADD → 릴레이 1회 → Kafka 컨슈머 → PostgreSQL `audit_events` 적재를 실제 컨테이너로 연결하고, `eventId`·`userId`·`delta`·`apiKeyId` 등 원본 필드와 릴레이 처리 후 Redis 미확인 메시지 0건(XPENDING)을 검증 | `KafkaAuditRoundTripTest` |
| Kafka 중복 전달 멱등 | 동일 `eventId`의 Kafka 메시지를 2회 전달해도 `(leaderboard_id, event_id)` UNIQUE와 `ON CONFLICT DO NOTHING`으로 `audit_events`가 1행인지 검증. 컨슈머 저장 멱등성의 검증이며 릴레이 장애 복구 검증은 아님 | `KafkaAuditRoundTripTest` |
| Kafka 컨슈머 재시작 | 첫 메시지의 DB 적재와 오프셋 커밋을 확인한 뒤 리스너를 중지한다. 중지 중 같은 파티션에 두 번째 메시지를 넣고 재시작해, 커밋된 오프셋 이후부터 소비하고 두 메시지를 각각 1행 저장하는지 검증 | `KafkaAuditRoundTripTest` |

Kafka 컨슈머 다중 인스턴스 실제 구동 검증(2026-08-11):

| 단계 | 파티션 할당 | 확인 결과 |
| --- | --- | --- |
| app-1만 실행 | app-1 = `0, 1, 2` | 모든 파티션의 컨슈머 지연(lag) 0 |
| app-2 추가 | app-1 = `0, 1`, app-2 = `2` | app-2가 파티션 2의 이벤트를 소비해 PostgreSQL에 1행 저장 |
| app-2 강제 종료 | 45초 뒤 app-1 = `0, 1, 2` | app-1이 파티션 2를 다시 할당받아 다음 이벤트를 저장했고 모든 파티션의 컨슈머 지연이 0으로 복귀 |

두 인스턴스는 같은 애플리케이션 이미지와 `audit-trend` 컨슈머 그룹을 사용했다. 릴레이는 app-1에서만 활성화하고 app-2에서는 비활성화했다. 이 결과는 Kafka 컨슈머의 파티션 재할당을 검증한 것이며, API 요청 분산이나 릴레이 다중화·처리량을 검증한 것은 아니다.

현재 릴레이 운용 범위는 **단일 인스턴스**입니다. 같은 컨슈머 이름으로 재시작하면 자기 PEL을 즉시 재처리하고, 다른 이름의 교체 릴레이가 오래된 PEL을 `XAUTOCLAIM`으로 인계하는 경로는 단건 Testcontainers 테스트까지 검증했습니다. 실제 10분 유휴·500건 초과 cursor·종료 주입·동시 다중 릴레이는 아직 검증하지 않았습니다. 상세 범위는 [SPIKE-001](docs/SPIKE-001-kafka-migration-path.md#현재-운용-경계와-phase-3-정책)에 있습니다.

수집된 벤치마크 결과:

| 테스트 | 현재 검증 결과 | 산출물 |
| --- | --- | --- |
| T1 Fixed | `300 users: p99 4.15ms / 995.67 RPS / fail 0` | `artifacts/k6/final/t1/t1-fixed-300-clean.json` |
| T1 Fixed | `500 users: p99 27.09ms / 998.67 RPS / fail 0` | `artifacts/k6/final/t1/t1-fixed-500.json` |
| T1 Fixed | `1000 users: p99 20.92ms / 997.93 RPS / fail 0` | `artifacts/k6/final/t1/t1-fixed-1000.json` |
| T1 Kafka E2E | `Redis 256MB / p99 3.99ms / 990.44 RPS / fail 0 / Kafka=PostgreSQL 300,001건 / 적체 0` | `artifacts/k6/final/t1/t1-kafka-e2e-256mb.json` |
| T3 Mixed | `write p99 1.47ms / read p99 1.65ms / fail 0.0032%` | `artifacts/k6/final/t3/t3-mixed-1000-clean.json` |
| T4 Idempotency | `new 1 / replay 49 / error 0` | `artifacts/k6/final/t4/t4-idempotency-clean.json` |
| T8 Scenario A | `30s snapshot / write p99 1.36ms / read p99 1.63ms / fail 0.009%` | `artifacts/k6/final/t8/t8-30s-1000.json` |
| T8 Scenario B | `5m snapshot / write p99 1.67ms / read p99 1.99ms / fail 0.0077%` | `artifacts/k6/final/t8/t8-5m-1000.json` |

메모:

- 공식 벤치마크 기준 환경은 Redis `256MB`입니다.
- 반복 개발 중 테스트 데이터 누적을 고려해 compose 개발 기본값은 `REDIS_MAXMEMORY=1gb`입니다.
- 공식 측정은 `REDIS_MAXMEMORY=256mb`를 명시해 실행했고, Redis 미수신·미확인 및 Kafka 미처리 메시지 0건까지 확인했습니다.

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
- `GET /internal/kafka/audit-topic/status`
- `GET /internal/audit-events/count`
- `GET /internal/circuit-breaker/status`

실제 요청 예시는 [docs/MANUAL_VERIFICATION.md](docs/MANUAL_VERIFICATION.md)에 있습니다.

## Design Notes

- 기능의 개수보다 운영 리스크(idempotency, hot key, snapshot lag, fail-fast)를 어떻게 다뤘는지에 초점을 둔 프로젝트입니다.
- Strong Consistency 대신 Eventually Consistent 모델을 명시적으로 채택했습니다.
- T8 비교 결과 `30초 snapshot 주기`가 기본 운영값으로 더 적절했습니다. `5분 주기`는 snapshot lag가 최대 5분까지 증가했지만 write/read p99 개선으로 이어지지 않았습니다.
