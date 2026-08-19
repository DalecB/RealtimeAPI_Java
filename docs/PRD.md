# Realtime Ranking & Event Processing API

랭킹 및 실시간 이벤트 집계에 특화된 REST API 서비스다.

Redis 기반 **Hot Path**와 PostgreSQL 기반 **Cold Path**를 분리하여 고빈도 Write 트래픽을 안정적으로 처리하는 백엔드 시스템이다.

---

## 0. Why / Scenario / Constraints

### Why (개발 동기)

모바일 게임/라이브 서비스 환경에서 랭킹/이벤트 점수 집계는 짧은 시간에 트래픽이 몰리는 대표적인 기능이다. 실제 운영에서는 다음 문제가 반복적으로 발생한다.

- 이벤트/프로모션/푸시로 특정 시간대에 **Write 트래픽이 급증**
- 네트워크 지연, 클라이언트 재시도, 버튼 연타로 **중복 요청**이 자주 발생
- "실시간 랭킹"을 위해 RDB에 직접 반영하면 **Lock 경쟁/비용/성능 문제**가 빠르게 발생
- 운영 중에는 "정확도(정합성)" 뿐 아니라 **장애 시 거동, 관측 가능성, 회복 시간**이 더 중요해진다

이 프로젝트의 목적은 "랭킹 기능 구현"이 아니라, 고빈도 이벤트 처리에서 발생하는 현실적인 문제(중복/폭주/편향/스냅샷 지연)를 설계로 풀고 **측정 가능한 근거(대시보드/리포트)로 검증**하는 것이다.

### Scenario (가정하는 운영 환경)

다음 서비스 규모를 기준으로 시스템을 설계한다.

| 항목                        | 가정값                     |
| --------------------------- | -------------------------- |
| DAU                         | 50만                       |
| 이벤트 집중 시간대          | 2시간 (푸시/프로모션 기준) |
| 유저당 평균 이벤트 액션 수  | 3회                        |
| 집중 시간 내 참여 유저 비율 | 20% (10만 명)              |

**Peak TPS 역산:**

```
집중 시간 내 총 이벤트 수 = 100,000 유저 × 3회 = 300,000건
균등 분포 기준 TPS = 300,000 / 7,200초 = 41.7 TPS
실제 트래픽은 균등하지 않으므로 피크 계수 10배 적용 = 417 TPS
안전 마진 2.5배 = 목표 1,000 TPS
```

따라서 **목표 처리량: Write 1,000 TPS** 를 시스템 설계 기준으로 삼는다.

추가 가정:

- 트래픽은 균등하지 않고 일부 leaderboard/일부 user로 편향 (**Hot Key**)
- 이벤트는 네트워크 환경에 따라 중복 전송될 수 있음
- 실시간 랭킹 조회는 **Top-N 중심**으로 발생하며 Write 트래픽과 동시에 발생
- 실시간 랭킹 전체 상태는 매 이벤트마다 관계형 DB에 동기 저장하지 않는다. 랭킹은 **Top-N 스냅샷**으로 복구하고, 원본 감사 이벤트는 별도의 비동기 파이프라인으로 영속화한다.

### Constraints (명시적 제약/스코프)

- Strong Consistency를 목표로 하지 않고 **Eventually Consistent** 모델 채택
- 실시간 처리(Hot Path)는 Redis를 **Source of Truth**로 두고, 영속 저장(Cold Path)은 PostgreSQL로 분리
- Hot Path에서는 PostgreSQL에 직접 쓰지 않는다. 랭킹은 **Top-N 스냅샷**으로 저장하고, 감사 이벤트는 Redis Streams 아웃박스 → Kafka → PostgreSQL 비동기 경로로 저장한다.
- 성능은 최대 TPS 수치가 아니라 **SLO(p99/오류율/회복 시간)**를 기준으로 평가한다.
- 배포/재현성: `docker compose up` 이후 **5분 내 실행/테스트 가능**해야 함
- 메시지 브로커: 1차 스코프에서는 Redis Streams만 사용했으나, ADR-009와 Phase 2에서 **Redis Streams 아웃박스 → Kafka → PostgreSQL** 감사 로그 파이프라인으로 확장

---

## 1. SLO (Service Level Objectives)

> 상용 운영 수준을 직접 주장하지 않는다. 이 문서에 선언한 SLO와 장애 범위를 수치와 테스트로 검증한다.
>
> 아래 SLO를 기준으로 k6 부하 테스트 결과를 평가한다.

### 1.1 API SLO

| API                                                      | p50    | p99    | Error Rate | 비고                          |
| -------------------------------------------------------- | ------ | ------ | ---------- | ----------------------------- |
| POST /events                                             | < 10ms | < 50ms | < 0.1%     | Hot Path, Redis Lua 처리 기준 |
| GET /leaderboards/{leaderboardId}/tops?offset=0&limit=50 | < 5ms  | < 20ms | < 0.1%     | Redis ZRANGE 기준             |
| GET /leaderboards/{leaderboardId}/users/{userId}         | < 5ms  | < 20ms | < 0.1%     | Redis ZSCORE + ZCOUNT 기준    |

### 1.2 운영 SLO

| 지표                            | 목표값                  | 측정 방법                                |
| ------------------------------- | ----------------------- | ---------------------------------------- |
| Snapshot Lag                    | < 45초 (30초 `fixedDelay` 기준) | `snapshot_lag_seconds` Prometheus 메트릭 |
| Snapshot 성공률                 | > 99%                   | `snapshot_failure_total` 기준            |
| Redis 장애 시 Fail Fast 응답    | < 500ms                 | Circuit Breaker OPEN 상태 기준           |
| Worker 재시작 후 적체 해소 시간 | < 2분                   | Streams 컨슈머 지연 기준                 |

### 1.3 Idempotency SLO

| 지표                                    | 목표값 |
| --------------------------------------- | ------ |
| 동일 키 중복 요청 시 점수 오염          | 0건    |
| 동시 100 요청 동일 키 → 정확히 1회 반영 | 100%   |

---

## 2. 기술 스택

### Backend

- Java 17
- Spring Boot 3.x
- REST API

### Data

- Redis 7.x (ZSET, Lua Script, Streams, AOF Persistence)
- PostgreSQL 15

### Persistence / ORM

- JPA (Hibernate)
- Flyway (Schema Migration + Seed)

### Observability

- Prometheus + Grafana (Metrics)
- Logback 텍스트 로그

### Test

- k6 (Load Test)
- JUnit 5 (Unit/Integration)

### Infra

- Docker Compose (재현 가능한 로컬 환경)

---

## 3. Architecture Overview

```
[Client]
    │
    ▼
[API Server (Spring Boot)]
    │
    ├──[Hot Path]
    │       └── Lua Script (Idempotency GET + ZINCRBY + SET EX + XADD)
    │               └── [Redis]  ←── Source of Truth
    │                       ├── ZSET: lb:{leaderboardId}:z
    │                       ├── STRING: lb:{leaderboardId}:idem:{eventUuid}
    │                       └── STREAM: lb:{leaderboardId}:events (outbox)
    │
    ├──[Audit Path]
    │       └── AuditRelayWorker → [Kafka: lb-audit-events]
    │                                   └── AuditEventConsumer
    │                                           └── PostgreSQL audit_events
    │
    └──[Cold Path]
            └── Snapshot Worker (PostgreSQL Advisory Lock)
                    └── Redis Top-1000 → PostgreSQL snapshot upsert

    [PostgreSQL]  ←── Source of Record
        ├── projects (프로젝트 정의)
        ├── leaderboards (리더보드 정의)
        ├── api_keys (인증/quota 관리)
        ├── snapshot_batches (스냅샷 메타)
        ├── snapshot_entries (Top-1000 스냅샷 행)
        ├── audit_events (Kafka에서 소비한 원본 감사 이벤트)
        └── usage_stats (운영 지표)
```

### 핵심 의도

- 실시간 트래픽은 Redis에서 처리하여 성능/확장성 확보
- Redis Streams는 Lua 원자성 안에서 감사 이벤트를 먼저 남기는 아웃박스로 사용
- Kafka는 감사 이벤트 전달과 컨슈머별 처리 위치를 담당하고, PostgreSQL은 조회 가능한 원본 감사 이벤트를 저장
- PostgreSQL snapshot은 Redis 콜드 스타트 복구 기준으로 사용
- Strong Consistency 대신 Eventually Consistent 모델을 채택하고, **Snapshot Lag를 핵심 운영 지표로 관리**

---

## 4. Data Flow

### 4.1 Write Flow (이벤트 처리)

```
클라이언트
  │
  │ POST /events
  │ Header: Authorization: Bearer {apiKey}
  │         Idempotency-Key: {uuid}
  │ Body:   { "leaderboardId": "string", "userId": "string", "deltaScore": number }
  │
  ▼
API Server
  │
  ├── API Key 인증 + Rate Limit 검사 (Redis 기반 Fixed Window)
  │
  ├── Circuit Breaker 상태 확인
  │     └── OPEN → 즉시 503 반환 (Fail Fast)
  │
  ├── Redis Lua Script 호출 (원자적 실행)
  │     ├── lb:{leaderboardId}:idem:{uuid} GET
  │     │     ├── 미존재 → ZINCRBY + SET(payloadHash) + XADD 실행 → {1, newScore} 반환
  │     │     └── 존재   → {0, storedPayloadHash} 즉시 반환 (ZINCRBY 실행 안 함)
  │     ├── ZINCRBY lb:{leaderboardId}:z {deltaScore} {userId}
  │     ├── SET lb:{leaderboardId}:idem:{uuid} {payloadHash} EX {TTL_SECONDS}
  │     └── XADD lb:{leaderboardId}:events * userId {userId} delta {deltaScore}
  │
  ├── [Java Layer] Lua 반환값 분기
  │     ├── isNew=1  → 200 OK { replayed: false }
  │     └── isNew=0  → storedPayloadHash vs incomingPayloadHash 비교
  │           ├── 일치   → 200 OK { replayed: true }  (정상 재시도)
  │           └── 불일치 → 409 Conflict { IDEMPOTENCY_KEY_REUSE_MISMATCH }
  │
  └── PostgreSQL에 직접 Write 없음
```

### 4.2 Audit Flow (감사 이벤트 전달)

```
Redis Stream: lb:{leaderboardId}:events
  │
  ├── AuditRelayWorker가 현재 릴레이의 미확인 메시지를 먼저 재처리
  ├── 다른 릴레이의 오래된 PEL을 XAUTOCLAIM으로 배치 인계
  ├── 새 메시지를 읽어 Kafka `lb-audit-events`에 발행
  └── 발행 성공을 확인한 메시지만 XACK
          │
          ▼
Kafka consumer group: audit-trend
  │
  ├── auto-commit 비활성화, 배치 처리 완료 후 오프셋 커밋
  └── PostgreSQL audit_events 저장
          └── UNIQUE (leaderboard_id, event_id)로 중복 저장 방지
```

Phase 2 완료 당시 릴레이는 단일 인스턴스와 고정 컨슈머 이름만 지원했다. 후속 Phase 3에서는 다른 이름의 교체 릴레이가 오래된 PEL을 `XAUTOCLAIM`으로 인계하도록 구현했다. Testcontainers에서는 운영값보다 짧은 유휴 시간과 작은 배치 크기를 사용해 `min-idle-time` 적용, 반환 커서 기반 후속 배치 처리, Kafka 발행 후 XACK 전 중복과 PostgreSQL 멱등 저장을 검증했다. 실제 10분 유휴 조건, 500건을 초과하는 PEL, 프로세스 강제 종료, 인계받은 릴레이의 재종료, 여러 릴레이의 동시 실행은 검증하지 않았다. 처리할 수 없는 메시지를 DLQ에 보관하는 정책은 확정했지만 아직 구현하지 않았다.

### 4.3 Read Flow (랭킹 조회)

```
실시간 Top-N 조회 (Hot):
  └── Redis ZREVRANGE lb:{leaderboardId}:z 0 {limit-1} WITHSCORES

특정 유저 조회:
  ├── Redis ZSCORE lb:{leaderboardId}:z {userId}  → score
  └── Redis ZCOUNT lb:{leaderboardId}:z ({score} +inf  → rank 계산 (Competition Ranking)

과거 랭킹/리포트 조회 (Cold):
  └── PostgreSQL snapshot_batches + snapshot_entries 조회

운영/정합성 검증 (Internal):
  ├── Snapshot 상태: snapshot_lag_seconds 조회
  └── Streams 상태: 릴레이 미수신 건수 / 미확인 건수
```

### 4.4 Snapshot Flow (Cold Path)

```
Snapshot Worker (30초 주기)
  │
  ├── PostgreSQL Advisory Lock 획득 시도
  │     (pg_try_advisory_lock(UUID 상·하위 64비트 XOR 값))
  │     └── 획득 실패 → Skip (중복 실행 방지)
  │
  ├── Redis ZREVRANGE Top-1000 조회
  │
  ├── [Empty Guard] 조회 결과 0건 시 → 저장 중단, Lock 해제
  │     (Cold Start 직후 빈 데이터로 스냅샷 덮어쓰기 방지)
  │
  ├── PostgreSQL Upsert
  │     ├── snapshot_batches: ON CONFLICT (leaderboard_id, snapshot_at) DO UPDATE
  │     └── snapshot_entries: ON CONFLICT (snapshot_id, user_id) DO UPDATE
  │
  ├── last_successful_snapshot_at 갱신
  │
  └── Advisory Lock 해제 (pg_advisory_unlock)
        └── 스냅샷 실패 시: 재시도 3회 → 알림 + snapshot_failure_total 증가
```

---

## 5. Ranking Policy

### 5.1 Competition Ranking (1,2,2,4 방식)

단순 `ZREVRANK + 1` 방식은 동점자 처리에서 틀린 순위를 반환한다. 이 시스템은 **Competition Ranking** 방식을 채택한다.

```
rank = ZCOUNT(lb:{leaderboardId}:z, ({myScore}, +inf]) + 1
```

- 자신보다 높은 점수를 가진 유저 수에 1을 더한 값이 순위
- 동점자가 2명이면 다음 순위는 4번 (1, 2, 2, 4)

**예시:**

| userId | score | rank |
| ------ | ----- | ---- |
| userA  | 1000  | 1    |
| userB  | 800   | 2    |
| userC  | 800   | 2    |
| userD  | 600   | 4    |

### 5.2 동점 내 정렬 (Tie-break)

Redis ZSET은 동일 score에 대해 member 값의 **lex 오름차순**으로 정렬한다. member = userId이므로 동점 시 userId lex 오름차순이 tie-break 기준이 된다. 별도 "먼저 등록 순" tie-break는 구현하지 않는다.

### 5.3 deltaScore 정책

| 항목         | 정책                                                          |
| ------------ | ------------------------------------------------------------- |
| 허용 범위    | 양의 정수만 허용 (1 이상)                                     |
| 음수 delta   | 허용하지 않음. 랭킹의 단조 증가(monotonic increase) 보장      |
| 최대값       | Long 범위 내 (Redis ZINCRBY는 double 처리, 정수 범위 내 사용) |
| 소수점       | 허용하지 않음 (integer only)                                  |
| 위반 시 응답 | 400 Bad Request + `INVALID_DELTA_SCORE`                       |

---

## 6. Redis Key 네이밍 규칙

모든 Redis Key는 `{leaderboardId}`를 hash tag로 사용하여 Redis Cluster 환경에서 동일 슬롯에 배치되도록 설계한다.

| 용도               | Key 형식                              | 타입   | TTL       |
| ------------------ | ------------------------------------- | ------ | --------- |
| 랭킹 ZSET          | `lb:{leaderboardId}:z`                | ZSET   | 없음      |
| Idempotency Key    | `lb:{leaderboardId}:idem:{eventUuid}` | STRING | 24시간    |
| Audit Log Stream   | `lb:{leaderboardId}:events`           | STREAM | 없음      |
| Rate Limit Counter | `rl:{apiKeyId}:{windowStart}`         | STRING | windowTTL |

> **leaderboardId 스코프**: leaderboardId는 UUID 기반 전역 고유값으로 관리한다. Redis Key에 projectId를 별도로 포함하지 않으며, projectId는 REST URL의 리소스 계층 표현에만 사용한다.

> **환경 분리 정책**: dev/stage/prod 간 키 충돌을 방지하기 위해 Redis DB index(`SELECT 0/1/2`) 또는 환경 prefix(`prod:lb:{leaderboardId}:z`) 중 하나를 운영 정책으로 강제한다. leaderboardId의 전역 유니크성은 DB unique constraint + UUID 생성 규칙으로 보장한다. 로컬 환경에서는 단일 Redis DB를 사용하며 prefix를 생략한다.

---

## 7. Lua Script 설계

### 7.1 원자 트랜잭션 범위

Lua Script는 Redis의 단일 스레드 특성을 활용하여 아래 **4개 연산을 원자적으로 처리**한다.

XADD를 Lua 내부에 포함함으로써 score 반영과 감사 로그 기록이 항상 함께 처리된다. score가 반영됐는데 Audit Log에 누락되는 상황을 원천 차단한다.

> **Cluster 키 슬롯 설계**: KEYS[1]~[3] 모두 `{leaderboardId}`를 hash tag로 사용하여 동일 슬롯에 배치.

```lua
-- KEYS[1] = "lb:{leaderboardId}:idem:{eventUuid}"
-- KEYS[2] = "lb:{leaderboardId}:z"
-- KEYS[3] = "lb:{leaderboardId}:events"
-- ARGV[1] = userId
-- ARGV[2] = deltaScore
-- ARGV[3] = ttlSeconds
-- ARGV[4] = 멱등 레코드 "v2:<payloadHash>:<processedAtEpochMillis>"
--           (payloadHash = userId + deltaScore 해시. processedAt은 replay 응답에 최초 처리 시각을 돌려주기 위해 함께 저장)
-- ARGV[5] = apiKeyId
-- ARGV[6] = 멱등키(eventUuid)

-- 1. 멱등키 중복 검사
local existing = redis.call('GET', KEYS[1])
if existing then
    -- 저장값을 그대로 반환. Java Layer가 해시를 비교해 200 replayed / 409를 판정한다.
    -- Lua도 해시만 잘라 비교하는데, 이는 판정이 아니라 conflict를 감사 기록에 남기기 위해서다.
    local existingHash = string.match(existing, "^v2:([^:]+):")
    local incomingHash = string.match(ARGV[4], "^v2:([^:]+):")
    if existingHash and incomingHash and existingHash ~= incomingHash then
        redis.call('XADD', KEYS[3], 'MAXLEN', '~', 100000, '*',
            'type', 'conflict', 'userId', ARGV[1], 'delta', ARGV[2],
            'apiKeyId', ARGV[5], 'idempotencyKey', ARGV[6])
    end
    return {0, existing}
end

-- 2. ZSET 점수 업데이트
local newScore = redis.call('ZINCRBY', KEYS[2], tonumber(ARGV[2]), ARGV[1])

-- 3. 멱등키 저장 (TTL 적용, v2 레코드 저장)
redis.call('SET', KEYS[1], ARGV[4], 'EX', tonumber(ARGV[3]))

-- 4. Audit Log Streams 기록 (score 반영과 원자적으로 처리)
-- MAXLEN ~ 100000: relay가 영구히 멈췄을 때 무한 증식을 막는 최후 방어선.
-- 평상시 정리는 relay 진행 기준 트림이 담당한다(SPIKE-001).
redis.call('XADD', KEYS[3], 'MAXLEN', '~', 100000, '*',
    'type', 'new', 'userId', ARGV[1], 'delta', ARGV[2],
    'apiKeyId', ARGV[5], 'idempotencyKey', ARGV[6])

return {1, newScore}  -- {isNew=true, newScore}
```

> replay(같은 payload 재요청)는 기록하지 않고 `conflict`만 남긴다. 판단 근거는 [SPIKE-001](SPIKE-001-kafka-migration-path.md).

### 7.2 Lua 제약 조건

| 항목        | 규칙                                            | 이유                                     |
| ----------- | ----------------------------------------------- | ---------------------------------------- |
| 연산 복잡도 | O(1) 또는 O(log N) 이하만 허용                  | Redis 단일 스레드 블로킹 방지            |
| 반복문      | 금지 (상수 횟수 연산만)                         | p99 latency 보호                         |
| KEYS 수     | 최대 3개 (모두 `{leaderboardId}` hash tag 적용) | Redis Cluster 키 슬롯 일치 요구사항 대비 |
| 모니터링    | `redis_lua_duration_ms` 메트릭 + Redis slowlog  | Lua 블로킹 조기 감지                     |

---

## 8. Snapshot Worker 설계

### 8.1 동시성 제어 (PostgreSQL Advisory Lock)

단일 인스턴스에서도, 다중 인스턴스에서도 중복 스냅샷이 발생하지 않아야 한다. Redis와 독립적으로 동작하여 Cold Path가 Hot Path 장애에 영향받지 않는다.

```
Lock 구현: PostgreSQL pg_try_advisory_lock(lockKey)
Lock Key: leaderboardId UUID의 상·하위 64비트를 XOR한 long 값

획득 방법: pg_try_advisory_lock(lockKey)  -- non-blocking, 즉시 반환
해제 조건:
  - 정상 완료 후 즉시 해제: pg_advisory_unlock(lockKey)
  - 커넥션 종료 시 자동 해제 (PostgreSQL 세션 종료)
  - 워커 크래시 시에도 커넥션 종료와 함께 Lock 자동 해제 보장

장점:
  - Redis 장애와 완전 독립
  - TTL/clock skew 리스크 없음
  - 소유자 검증 불필요 (세션 연결이 Lock 식별자임)
```

### 8.2 Empty Guard (Snapshot Overwrite 방지)

Redis 재시작 직후 또는 Cold Start 시 ZSET이 비어 있는 상태에서 스냅샷이 실행되면 기존 PostgreSQL 데이터를 빈 데이터로 덮어쓸 위험이 있다. 이를 방지하기 위해 다음 조건을 적용한다.

```
조회 결과 size == 0 → 스냅샷 저장 중단
  └── WARN 로그 출력: "Snapshot skipped: empty Redis ZSET detected"
  └── Advisory Lock 해제
  └── snapshot_skip_total 메트릭 증가
```

### 8.3 재시도 및 장애 처리

```
스냅샷 실패 시:
  1. 즉시 재시도 1회
  2. 5초 후 재시도 2회
  3. 재시도 3회 모두 실패 → 알림 발송 + snapshot_failure_total 메트릭 증가
  4. 다음 스케줄 주기에 정상 재개

Upsert 원자성 보장:
  - Top-1000 전체 Upsert는 @Transactional로 묶어 All-or-Nothing으로 처리한다.
  - 500번째 Insert 중 DB 커넥션 단절 등 예외 발생 시 해당 배치 전체가 롤백되며,
    반쪽짜리 스냅샷이 저장되는 상황을 방지한다.

Worker 재시작 시:
  - PostgreSQL 커넥션 종료와 함께 Advisory Lock 자동 해제
  - 다음 스케줄 주기에 정상 재개
  - Streams consumer group을 통해 Audit Log 상태 확인 가능

중복 스냅샷 방지:
  - snapshot_batches Upsert로 동일 (leaderboard_id, snapshot_at) 중복 삽입 방지
  - snapshot_entries Upsert로 동일 (snapshot_id, user_id) 중복 삽입 방지
  - Lock 획득 실패 시 Skip (중복 실행 자체를 막음)
```

### 8.4 Snapshot Top-N 설정

**N = 1,000** (확정)

```
근거:
  - 30초 주기 × 1일 = 2,880 snapshot
  - 2,880 snapshot × 1,000 rows = 2,880,000 rows/day/leaderboard
  - PostgreSQL 기준 행당 약 100bytes → 약 288MB/day/leaderboard
  - RTO: Cold Start 시 Top-1000 복구 → ZREVRANGE 1,000건 단일 배치로 수초 내 완료
  - N=10,000은 복구 시간 및 snapshot 실행 시 DB 부하 급증으로 부적합
```

### 8.5 Snapshot 데이터 모델

```sql
CREATE TABLE snapshot_batches (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    leaderboard_id  UUID NOT NULL REFERENCES leaderboards(id) ON DELETE CASCADE,
    snapshot_at     TIMESTAMPTZ NOT NULL,
    top_n           INT NOT NULL DEFAULT 1000 CHECK (top_n > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (leaderboard_id, snapshot_at)
);

CREATE TABLE snapshot_entries (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    snapshot_id     BIGINT NOT NULL REFERENCES snapshot_batches(id) ON DELETE CASCADE,
    rank            INT NOT NULL CHECK (rank > 0),
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score           BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (snapshot_id, user_id)
);

-- 배치 조회 최적화
CREATE INDEX idx_snapshot_batches_lookup
    ON snapshot_batches (leaderboard_id, snapshot_at DESC);

-- 배치 내 Top-N 조회 최적화
CREATE INDEX idx_snapshot_entries_snapshot_rank_user
    ON snapshot_entries (snapshot_id, rank ASC, user_id ASC);
```

---

## 9. Redis 내구성 전략 (계층별 RPO)

Redis를 Source of Truth로 사용하는 구조에서 데이터 유실 허용 범위(RPO)는 계층별로 다음과 같이 정의된다.

| 계층                | 설정                                      | RPO (최대 유실)                  | 역할                                       |
| ------------------- | ----------------------------------------- | -------------------------------- | ------------------------------------------ |
| AOF                 | `appendfsync everysec`                    | 최대 약 1초                      | Redis 재시작 시 1차 복구                   |
| RDB                 | `3600 1 / 300 100 / 60 10000` 변경량 기반 | 성공한 BGSAVE 기준 최대 약 1시간 | AOF 복구 불가 시 운영자가 선택할 복구 후보 |
| PostgreSQL Snapshot | 30초 주기                                 | Top-1,000 기준 최대 약 30초      | 랭킹 Top-1,000 콜드 스타트 복구            |
| DB Full Backup      | 현재 미구현                               | 보장하지 않음                    | 현재 프로젝트 범위 밖                      |

> **현재 보장 범위**: 정상 재시작은 AOF `everysec` 기준 최대 약 1초 유실을 목표로 한다. RDB는 고정 주기가 아니라 변경량에 따라 생성되며, AOF 손상 시 자동 전환하지 않는다. PostgreSQL 스냅샷 복구는 랭킹 Top-1,000까지만 대상으로 하며, 멱등키와 Redis Stream 상태는 복구하지 않는다.

### Cold Start 복구 플로우

Redis 재시작 후 ZSET이 비어 있는 상태를 감지하면 다음 순서로 복구한다.

```
1. Redis 재시작 → AOF 복구 시도 (everysec 기준, 최대 1초 유실)
   └── AOF 복구 성공 → 정상 운영 재개

2. AOF 복구 불가 시 → 운영자가 최신 RDB를 선택해 수동 복구
   └── 성공한 BGSAVE 기준 최대 약 1시간 유실
   └── 자동 전환과 실패 주입 검증은 현재 범위에서 지원하지 않음

3. RDB도 유실 시 → PostgreSQL 최신 snapshot 기준 복구
   └── 복구 대상: snapshot_batches에서 leaderboard별 최신 snapshot_at 1건을 찾고,
                 해당 snapshot_id의 snapshot_entries Top-1000을 복구
   └── 복구 방법: ZADD lb:{leaderboardId}:z {score} {userId} 배치 실행
   └── 복구 완료 판단: ZCARD lb:{leaderboardId}:z > 0 확인
   └── 복구 중 Write 처리: Circuit Breaker 수동 OPEN으로 Write 차단 (데이터 오염 방지)
   └── 복구 완료 후 Circuit Breaker 정상화 → Write 재개
   └── 복구 범위는 랭킹 Top-1,000이며 멱등키·Redis Stream 상태는 제외
```

---

## 10. Redis SPOF 대응 전략

### 10.1 장애 시나리오별 대응

| 시나리오              | 대응 방식                                     | 비고                                 |
| --------------------- | --------------------------------------------- | ------------------------------------ |
| Redis 일시 응답 지연  | Circuit Breaker (Closed → Half-Open → Open)   | Resilience4j 활용                    |
| Redis 완전 장애       | Circuit Breaker OPEN → 503 즉시 반환          | Write 요청 차단으로 데이터 오염 방지 |
| Redis 재시작          | AOF Persistence로 데이터 복구 (최대 1초 유실) | AOF fsync: everysec                  |
| Redis + AOF 동시 유실 | PostgreSQL Snapshot 기준 Cold Start 복구      | 랭킹 Top-1,000까지만 복구, 최대 약 30초 |
| Snapshot Worker 실패  | 재시도 3회 후 알림, 다음 주기 재개            | Cold Path 장애는 Hot Path와 독립     |

### 10.2 Circuit Breaker 설정

```
라이브러리: Resilience4j CircuitBreaker

설정:
  slidingWindowSize: 10 (최근 10회 요청 기준)
  failureRateThreshold: 50% (5회 이상 실패 시 OPEN)
  waitDurationInOpenState: 10초
  permittedCallsInHalfOpenState: 3

OPEN 상태 응답:
  HTTP 503 + Retry-After: 10 헤더
  → 클라이언트가 exponential backoff 재시도 가능하도록 유도

Retry 정책 (클라이언트 권고):
  - 초기 대기: 1초
  - 배수: 2배
  - jitter: ±20%
  - 최대 재시도: 3회
```

### 10.3 명시적으로 구현하지 않는 것 (스코프 외)

- Redis Sentinel (HA): 본 프로젝트 범위 외, 향후 확장 방향으로 문서화만
- Redis Cluster (샤딩): 동일

---

## 11. Rate Limit & Quota 정책

### 11.1 구현 방식

**Redis 기반 Fixed Window Counter** 직접 구현 (외부 라이브러리 미사용)

선택 이유: Bucket4j 등 라이브러리 사용 시 Redis와의 분리 비용 발생. Rate Limit 자체가 Redis 의존적이므로 Lua Script로 원자적 구현이 더 일관성 있음.

> **Fixed Window vs Sliding Window**: 현 구현은 `rl:{apiKeyId}:{windowStart}` 키에 INCR/EXPIRE를 적용하는 **Fixed Window** 방식이다. 윈도우 경계에서 최대 2배의 버스트가 발생할 수 있지만, 현재 프로젝트에 필요한 제한 기능을 더 낮은 구현 복잡도로 제공하므로 Fixed Window를 선택한다.

```lua
-- Fixed Window Rate Limit Lua Script
-- KEYS[1] = "rl:{apiKeyId}:{windowStart}"
-- ARGV[1] = limit (최대 허용 횟수)
-- ARGV[2] = windowTTL (초)

local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end
if current > tonumber(ARGV[1]) then
    return 0  -- 차단
end
return 1  -- 허용
```

### 11.2 정책 목표

Rate Limit은 단순 제한이 아니라 **정상 API Key의 SLO를 보호**하는 정책이다.

| 정책                  | 기준                                                   |
| --------------------- | ------------------------------------------------------ |
| 기본 Rate Limit       | API Key당 초당 100 요청                                |
| 일일 Quota            | API Key당 100만 요청                                   |
| 초과 응답             | HTTP 429 + `X-RateLimit-Remaining`, `Retry-After` 헤더 |
| 악성 트래픽 차단 효과 | T7 테스트에서 attackKey vs normalKey 격리 증명         |

---

## 12. Idempotency 정책

### 12.1 TTL 기준 처리 정책

| 상황                               | 처리 방식                     | 응답                                            |
| ---------------------------------- | ----------------------------- | ----------------------------------------------- |
| TTL 내 동일 Key + 동일 payload     | 중복으로 간주, 점수 미반영    | 200 OK + `replayed: true`                       |
| TTL 내 동일 Key + **다른 payload** | 재사용 오류                   | 409 Conflict + `IDEMPOTENCY_KEY_REUSE_MISMATCH` |
| TTL 이후 동일 Key 재요청           | 신규 요청으로 간주, 점수 반영 | 200 OK + `replayed: false`                      |
| TTL 경계 레이스 컨디션             | Lua Script 원자성으로 처리    | 별도 보호 불필요                                |

> **payload 비교 기준**: `(userId, deltaScore)` 조합. Key 저장 시 두 필드의 해시값을 Redis에 함께 저장하여 비교.

### 12.2 Idempotency 보장 범위

**TTL window(24시간) 내에서만 보장**. TTL 이후 동일 Key 재사용은 신규 요청으로 처리되므로 중복 반영이 발생할 수 있다. 클라이언트는 이벤트 발생 기준 24시간 이내에 재시도해야 한다.

TTL 기본값: **24시간** (일 단위 이벤트 기준)

---

## 13. Component Responsibility

### 13.1 Redis (Hot Path – Source of Truth)

- 실시간 랭킹 데이터 관리 (ZSET)
- 멱등키 저장 및 중복 요청 방지 (STRING + TTL)
- Rate Limit 카운터 (STRING + Lua, Fixed Window)
- Lua Script를 통한 원자성 보장 (Idempotency + ZINCRBY + Key저장 + XADD 4연산 원자적)
- Redis Streams를 감사 이벤트 아웃박스로 사용 (score 반영과 원자적 기록)
  - `audit-relay` Consumer Group이 `XREADGROUP`/`XACK`으로 전달 상태를 관리하고, 교체 릴레이는 `XAUTOCLAIM`으로 오래된 PEL을 인계
  - 평상시에는 Consumer Group 진행 워터마크 기준 `MINID` 트림을 사용하고, `MAXLEN ~ 100000`은 릴레이가 장기간 멈췄을 때 무한 증가를 막는 최후 안전장치

### 13.2 PostgreSQL (Cold Path – Source of Record)

- 프로젝트/리더보드 정의 및 설정
- API Key 관리 (인증/quota)
- 사용량 통계 저장
- 랭킹 스냅샷 (Top-1000, 30초 주기)

---

## 14. Snapshot & Data Retention Policy

### 스냅샷 정책

- 실시간 전체 랭킹은 PostgreSQL에 저장하지 않음
- 스냅샷 범위는 **Top-1,000까지만** 허용 (전체 스캔 금지)
- 기본 주기: 30초 (운영 목표에 따라 조정 가능)
- Empty Guard 적용: Redis ZSET이 비어 있는 경우 저장 금지

### Snapshot Lag 관리

| 주기 | 예상 Lag | DB 부하 | 적합한 상황                  |
| ---- | -------- | ------- | ---------------------------- |
| 10초 | < 15초   | 높음    | 라이브 이벤트, 실시간성 중요 |
| 30초 | < 45초   | 중간    | 기본값·SLO (`fixedDelay`와 처리 시간 포함) |
| 5분  | < 6분    | 낮음    | 일반 운영, 비용 절감         |

T8 테스트에서 주기 2종(30초 vs 5분) 각각에서 Mixed Workload 실행, lag/latency trade-off를 수치화한다.

---

## 15. Observability (운영 관측)

### Metrics (Prometheus)

**HTTP Layer**

- `http_server_requests_seconds` (uri, method, status 라벨)
- `http_server_requests_seconds_count`

**Hot Path**

- `redis_lua_duration_ms`: Lua Script 실행 시간
- `idempotency_hit_total`: 중복 요청 감지 횟수 (replayed)
- `idempotency_miss_total`: 신규 요청 처리 횟수
- `idempotency_conflict_total`: 409 payload 불일치 횟수
- `rate_limit_block_total` (apiKeyId 라벨): Rate Limit 차단 횟수

**Circuit Breaker**

- `circuit_breaker_state` (closed/half_open/open)
- `circuit_breaker_failure_rate`

**Cold Path**

- `snapshot_duration_seconds`: 스냅샷 처리 시간
- `snapshot_lag_seconds`: 마지막 성공 스냅샷 이후 경과 시간
- `snapshot_failure_total`: 스냅샷 실패 횟수
- `snapshot_skip_total`: Empty Guard로 인한 스냅샷 스킵 횟수

**Streams**

- `stream_consumer_group_lag`: 릴레이 컨슈머 그룹(`audit-relay`)이 아직 읽지 않은 메시지 건수
- `stream_pending_entries`: 릴레이가 읽었지만 아직 XACK하지 않은 메시지 건수(XPENDING)
- `stream_length`: audit stream 길이(XLEN). 리텐션의 결과이지 밀린 양이 아니다

### Logs

현재는 Spring Boot 기본 Logback 텍스트 로그를 사용한다. JSON 구조화 로그는 운영 배포 범위에서 검토한다.

---

## 16. API Surface

### Public API

**이벤트 처리**

```
POST /events
Headers:
  Authorization: Bearer {apiKey}
  Idempotency-Key: {uuid}
Body:
  { "leaderboardId": "string", "userId": "string", "deltaScore": number }

Response:
  200 OK:
    {
      "idempotencyKey": "uuid",
      "replayed": false,
      "processedAt": "ISO8601"
    }
  200 OK (중복 요청):
    {
      "idempotencyKey": "uuid",
      "replayed": true,
      "processedAt": "ISO8601"  // 최초 처리 시각
    }
  400 Bad Request: deltaScore 음수/소수점/0 등 유효하지 않은 값
    { "errorCode": "INVALID_DELTA_SCORE" }
  409 Conflict: 동일 Idempotency-Key + 다른 payload
    { "errorCode": "IDEMPOTENCY_KEY_REUSE_MISMATCH" }
  429 Too Many Requests: Rate Limit 초과
    Headers: X-RateLimit-Remaining: 0, Retry-After: {seconds}
  503 Service Unavailable: Redis Circuit Breaker OPEN
    Headers: Retry-After: 10
```

> **설계 철학**: POST /events는 "이벤트 처리 확인" 중심 응답을 반환한다. 처리 후 score/rank를 응답에 포함하지 않는다. 클라이언트가 순위/점수 정보가 필요하다면 GET으로 별도 조회한다. 이는 replay 시 과거 score 반환으로 인한 혼란을 방지하고, POST의 책임을 명확히 분리하기 위한 결정이다.

**랭킹 조회 (Top-N)**

```
GET leaderboards/{leaderboardId}/tops
Query Parameters:
  offset: integer, default 0, min 0, max 9999
  limit:  integer, default 50, min 1, max 100

Response:
  200 OK:
    {
      "leaderboardId": "string",
      "items": [{ "rank": 1, "userId": "string", "score": number }],
      "total": number  // ZCARD(lb:{leaderboardId}:z): 전체 참여자 수, O(1)
    }
  400 Bad Request: offset > 9999 또는 limit > 100
    { "errorCode": "PAGINATION_LIMIT_EXCEEDED" }
```

> **Pagination 정책**: offset 최대값 9,999 제한. offset + limit이 Top-1,000 snapshot 범위를 초과하는 경우 Redis 실시간 데이터 기준으로 반환하되, 깊은 페이지네이션은 의도적으로 제한한다. Cursor 기반 전환은 Non-goals.

**특정 유저 조회**

```
GET leaderboards/{leaderboardId}/users/{userId}

Response:
  200 OK (참여 이력 있음):
    { "userId": "string", "score": number, "rank": number }
  200 OK (참여 이력 없음):
    { "userId": "string", "score": 0, "rank": null }
```

> **미참여 유저 처리**: 404가 아닌 200 + rank=null, score=0 반환. 랭킹 도메인에서 "참여 이력 없음"은 에러가 아니라 상태다. 클라이언트가 null 체크 없이 score=0으로 처리할 수 있어 구현이 단순해진다.

### Internal / Debug API (운영/정합성 검증)

```
GET /internal/snapshot/status
  → { "lastSuccessfulSnapshotAt": "ISO8601", "snapshotLagSeconds": number }

GET /internal/streams/status
  → { "pendingEntries": number, "streamLength": number, "consumerGroupLag": number }

GET /internal/kafka/audit-topic/status
  → { "totalMessages": number, "retained": number, "consumerLag": number }

GET /internal/audit-events/count
  → { "count": number }

GET /internal/circuit-breaker/status
  → { "state": "CLOSED|HALF_OPEN|OPEN", "failureRate": number }
```

> **현재 구현 메모**: `consumerGroupLag`는 릴레이가 아직 읽지 않은 건수, `pendingEntries`는 읽었지만 아직 Kafka로 옮기지 못해 XACK하지 않은 건수다. `streamLength`는 이미 처리한 메시지도 포함하는 스트림 전체 길이이므로 적체량으로 사용하지 않는다. 상세는 [SPIKE-001](SPIKE-001-kafka-migration-path.md).

### Admin / Seed (테스트 자동화)

```
POST /users                   → 테스트/관리용 유저 생성
POST /auth/login              → 기존 user externalId 기반 demo admin JWT 발급
POST /projects                → 프로젝트 생성
POST /leaderboards            → 리더보드 생성
POST /admin/api-keys          → API Key 발급 (quota 설정 포함)
```

또는 Flyway seed SQL로 대체 가능.

> **인증 스코프 메모**: 현재 구현의 admin auth는 본 프로젝트 범위상 password/OAuth 없이 `externalId` 기반 JWT 발급을 사용한다. 추후 auth scope 확장 시 교체 가능하도록 `/auth/login` 계약만 유지한다.

---

## 17. Non-goals (의도적으로 제외한 항목)

| 항목                         | 제외 이유                                                                                                                                        |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| WebSocket 기반 실시간 Push   | 복잡도 대비 본 프로젝트 범위 밖                                                                                                                  |
| 모든 이벤트의 영구 보관      | 운영 비용 및 스토리지 현실적 제약                                                                                                                |
| Strong Consistency 보장      | Eventually Consistent 선택과 트레이드오프 관계                                                                                                   |
| Redis Sentinel / Cluster     | 본 프로젝트 범위 외, 향후 확장 방향만 문서화                                                                                                     |
| ~~Kafka 기반 메시지 브로커~~ | ~~Redis Streams로 감사 로그 파이프라인 충분히 증명 가능~~ → **ADR-009에서 대체됨.** 보관 30일·복수 소비자를 가정으로 선언하면서 Kafka를 채택했다 |
| Redis 데이터 유실 후 Audit Stream 복구 | PostgreSQL 스냅샷은 Top-K 랭킹만 복구하며 Audit Stream 원본은 복구하지 않는다. 현재 Phase 3의 복구 범위는 Redis에 남아 있는 PEL을 교체 릴레이가 인계하는 동작까지다. |
| Top API Cursor Pagination    | offset 기반 + 상한 제한으로 운영 범위 충분                                                                                                       |

---

## 18. Benchmark & Reliability Test Plan (k6)

> **정본 표시**: 이 절은 벤치마크의 **의도와 성공 기준**(무엇을 왜 재는가)을 정한다. 실행 절차·명령·옵션의 정본은 [k6/BENCHMARK_RUNBOOK.md](../k6/BENCHMARK_RUNBOOK.md)다. 둘이 어긋나면 실행 방법은 런북을, 판정 기준은 이 절을 따른다.

### 18.0 목적

이 섹션은 최대 성능 수치를 제시하는 것이 아니라 아래 3가지를 증명하기 위한 계획이다.

- 선언한 SLO와 장애 범위를 충족하는가? (지연 시간, 오류율, 복구 가능성)
- 이 시스템의 핵심 리스크(중복/편향/폭주/스냅샷 지연)를 이해했는가?
- 측정 결과로 트레이드오프를 설명할 수 있는가?

### 18.1 테스트 환경 (재현 가능성 보장)

| 항목             | 사양                                         |
| ---------------- | -------------------------------------------- |
| 테스트 실행 환경 | Docker Compose (로컬 Mac M-series)           |
| API Server       | Spring Boot, JVM heap 512MB                  |
| Redis            | 7.x, AOF everysec, maxmemory 256MB           |
| PostgreSQL       | 15.x, max_connections 100                    |
| k6 실행          | 로컬 동일 머신 (네트워크 오버헤드 배제 목적) |

> ⚠️ 로컬 환경 수치임을 결과 보고서에 명시. 절대값보다 SLO 달성 여부와 병목 분석이 핵심.

### 18.2 테스트 Tier 정의

**Tier 0 (Must-have, 결과 공개 필수)**

| 테스트                          | 목적                           | 핵심 검증 지표                   |
| ------------------------------- | ------------------------------ | -------------------------------- |
| T1: Hot Path Write Throughput   | 1,000 TPS 목표 달성 여부       | p99 < 50ms, error rate < 0.1%    |
| T3: Mixed Workload (Write+Read) | Read SLO 보호 여부             | Read p99 < 20ms (Write 부하 중)  |
| T4: Idempotency Correctness     | 동시 중복 요청 정합성          | 점수 오염 0건, 200 OK 정상 반환  |
| T8: Snapshot Pipeline Impact    | Snapshot lag/latency trade-off | lag < 45초, Write p99 영향 < 10% |

**Tier 1 (Differentiator)**

| 테스트                       | 목적                                            |
| ---------------------------- | ----------------------------------------------- |
| T5: Hot Key / Skew Test      | 편향 트래픽에서 p99 보호 여부                   |
| T6: Spike / Burst Resilience | 급격한 트래픽 증가 시 Circuit Breaker 동작 확인 |
| T7: Rate Limit Enforcement   | normalKey vs attackKey SLO 격리 증명            |

**Tier 2 (Optional)**

| 테스트                         | 목적                                       |
| ------------------------------ | ------------------------------------------ |
| T2: Read Performance (limit별) | Top-100 vs Top-1000 응답시간 비교          |
| T9: Soak Test (30분+)          | TTL 만료/메모리 드리프트/Lag 장시간 안정성 |

### 18.3 Tier 0 상세 설계

**T1: Hot Path Write Throughput**

```
시나리오: POST /events, arrival-rate 방식으로 100→500→1000→1500 TPS 단계 상승
분포: 균등 분포 (leaderboard 1개, userId 무작위)
기간: 각 단계 2분, 총 8분
측정: p50/p95/p99/error rate/Redis CPU
성공 기준: 1,000 TPS에서 p99 < 50ms, error rate < 0.1%
```

**T3: Mixed Workload**

```
시나리오: Write 80% + Read 20% (1,000 TPS 기준 Write 800, Read 200)
추가: Write 95% + Read 5% 비율로 동일 반복
측정: Write p99 / Read p99 분리 측정
성공 기준: Read p99 < 20ms (Write 부하 중에도 보호)
```

**T4: Idempotency Correctness Under Concurrency**

```
시나리오:
  1. 동일 Idempotency-Key로 VU 50개가 동시에 POST
  2. 기대 결과: ZSET 점수 정확히 1회만 반영
검증: GET /leaderboards/{leaderboardId}/users/{userId}로
      실제 점수 vs 기대값 비교표 생성
성공 기준: 점수 오염 0건, 50개 중 1개만 신규 처리, 나머지 49개 replayed: true
```

**T8: Snapshot Pipeline Impact**

```
시나리오 A: Snapshot 주기 30초, Mixed Workload 1,000 TPS 10분
시나리오 B: Snapshot 주기 5분, 동일 조건
측정:
  - snapshot_lag_seconds (시나리오별 비교)
  - Write p99 (스냅샷 실행 중 vs 비실행 중 비교)
결론: lag/latency trade-off 수치화 → 운영 주기 선택 근거 제시
```

### 18.4 결과 리포팅 템플릿 (고정)

각 테스트는 아래 형식으로 문서화한다.

```markdown
## T1: Hot Path Write Throughput

### SLO 목표

- p99 < 50ms at 1,000 TPS
- error rate < 0.1%

### 환경

- 스펙: Apple M2, Docker Compose, Redis 7.2, PostgreSQL 15
- 설정: JVM heap 512MB, Redis maxmemory 256MB

### 시나리오

- arrival-rate: 100 → 500 → 1000 → 1500 TPS (단계별 2분)
- 분포: leaderboard 1개, userId 무작위 10만 명

### 결과

| TPS  | p50 | p95 | p99 | Error Rate |
| ---- | --- | --- | --- | ---------- |
| 100  | Xms | Xms | Xms | X%         |
| 500  | Xms | Xms | Xms | X%         |
| 1000 | Xms | Xms | Xms | X%         |
| 1500 | Xms | Xms | Xms | X%         |

### 병목 분석

- (메트릭/로그 스크린샷 첨부)

### 결론

- 안정 TPS: N
- 병목 원인: X
- SLO 달성 여부: ✅/❌
```

---

## 19. Risk & Scope Decisions (ADR)

### ADR-001: Redis를 Hot Path Source of Truth로 선택

**상황:** 고빈도 Write 트래픽을 처리하기 위해 PostgreSQL에 직접 반영하는 방식과 Redis 중간 계층을 사용하는 방식 중 하나를 선택해야 했다.

**결정:** Redis ZSET을 실시간 랭킹의 Source of Truth로 채택했다.

**근거:** PostgreSQL Row Lock 경쟁은 목표 TPS인 1,000에서 병목이 된다. Redis ZSET은 O(log N) 복잡도와 단일 스레드 특성을 바탕으로 고빈도 쓰기 요청을 처리할 수 있다.

**트레이드오프:** Redis에 장애가 발생하면 쓰기 기능 전체가 중단된다. AOF, Circuit Breaker와 PostgreSQL 스냅샷으로 이 위험을 완화한다.

---

### ADR-002: Lua Script 원자성 범위 결정

**상황:** 멱등성 확인, ZINCRBY, 키 저장과 이벤트 기록을 원자적으로 처리해야 했다.

**결정:** 네 연산을 모두 단일 Lua Script에 포함했다(Idempotency GET + ZINCRBY + SET EX + XADD).

**근거:** 점수 반영과 Audit Log 기록이 원자적으로 함께 동작해야 한다. 이 구조는 점수만 반영되고 Streams 기록이 누락되는 경우를 제거한다. XADD는 O(1) 상수 시간 연산이므로 추가되는 Lua 블로킹 위험도 제한적이다.

**트레이드오프:** Lua Script에서 세 개의 KEYS를 사용한다. Redis Cluster 환경에서는 모든 키에 `{leaderboardId}` 해시 태그를 적용해야 한다.

**후속 (2026-07-29):** ADR-009가 Kafka를 채택하면서 이 결정의 유지 여부가 쟁점이 됐다. Kafka producer는 Lua 블록 안에 들어갈 수 없기 때문이다. 검토 결과 **본 결정은 유지된다.** Lua의 4연산 원자성은 그대로 두고, 스트림의 역할만 최종 저장소에서 **outbox**로 바뀐다. 별도 relay가 스트림을 읽어 Kafka로 옮긴다. 선택 근거는 [SPIKE-001](SPIKE-001-kafka-migration-path.md)의 선결 쟁점 항목에 있다.

---

### ADR-003: Snapshot Worker 분산 락 구현 방식

**상황:** 다중 인스턴스 배포에서 Snapshot Worker가 중복으로 실행되지 않도록 Redis 기반 SET NX EX와 PostgreSQL Advisory Lock 중 하나를 선택해야 했다.

**결정:** PostgreSQL `pg_try_advisory_lock` 기반 분산 락을 구현했다.

**근거:** Cold Path의 Snapshot Worker는 Redis 장애와 독립적으로 동작해야 한다. Redis SET NX EX 방식은 Hot Path 장애 시 락을 획득할 수 없어 Cold Path까지 연쇄적으로 중단될 수 있다. PostgreSQL Advisory Lock은 세션이 종료되면 자동으로 해제되므로 워커가 비정상 종료돼도 락이 남지 않으며, TTL이나 시계 오차도 고려할 필요가 없다.

**트레이드오프:** PostgreSQL 커넥션을 사용한다. 다만 Snapshot Worker는 30초마다 한 번 실행되므로 커넥션 점유 시간은 짧다. 락 키는 `leaderboardId` UUID의 상·하위 64비트를 XOR한 `long` 값을 사용한다.

---

### ADR-004: Rate Limit 구현 방식

**상황:** API Key 기반 Rate Limit을 구현하기 위해 Redis Lua와 Bucket4j, Fixed Window와 Sliding Window를 비교해야 했다.

**결정:** Redis Lua Script 기반 Fixed Window Counter를 직접 구현했다.

**근거:** Redis는 이미 Hot Path의 필수 의존 구성 요소다. Bucket4j 같은 외부 라이브러리를 도입하면 Redis 연동 계층을 별도로 관리해야 한다. Sliding Window는 시간 구간별 레코드가 필요해 구현 복잡도가 증가한다. Fixed Window는 INCR/EXPIRE와 Lua 원자성을 사용해 단순하게 구현할 수 있으며, 현재 프로젝트 범위에서 요구하는 제한 기능을 충족한다.

**트레이드오프:** 윈도우 경계에서는 최대 두 배의 버스트가 발생할 수 있다. Redis 장애 시 Rate Limit도 함께 중단되지만 Circuit Breaker가 Redis 장애를 처리하므로 현재 범위에서는 허용한다.

---

### ADR-005: Redis SPOF 위험 완화 수준

**상황:** Redis 단일 노드 장애가 서비스 전체에 영향을 준다.

**결정:** Redis Sentinel과 Cluster는 프로젝트 범위에서 제외하고, Circuit Breaker, AOF와 PostgreSQL 스냅샷으로 위험을 완화한다.

**근거:** 이 프로젝트에서는 HA 인프라 구성보다 장애 시 동작과 복구 방식을 검증하는 작업을 우선한다.

**트레이드오프:** Redis 장애 시 쓰기 기능의 중단을 피할 수 없다. 고가용성이 필요해지면 Sentinel 도입을 검토한다.

---

### ADR-006: Competition Ranking 방식 선택

**상황:** ZREVRANK+1을 사용하는 단순 순위와 Competition Ranking(1,2,2,4) 중 하나를 선택해야 했다.

**결정:** `rank = ZCOUNT(key, ({myScore}, +inf]) + 1` 방식을 채택했다.

**근거:** ZREVRANK+1은 동점자를 서로 다른 순위로 반환하여 "같은 점수면 같은 순위"라는 사용자 기대를 위반한다. 게임과 랭킹 도메인에서는 Competition Ranking이 일반적으로 사용되며, ZCOUNT의 O(log N) 연산 비용은 현재 성능 목표 안에서 허용할 수 있다.

**트레이드오프:** 조회할 때 ZSCORE와 ZCOUNT를 각각 실행하므로 두 번의 RTT가 필요하다. 다만 `GET /users/{userId}`는 현재 SLO인 p99 20ms 안에서 동작한다. Top API는 ZREVRANGE 결과에 순위를 순차적으로 부여하므로 별도의 ZCOUNT가 필요하지 않다. 읽기 TPS가 임계점을 넘으면 두 연산을 단일 Lua Script나 Redis Pipeline으로 묶어 한 번의 RTT로 줄일 수 있다.

---

### ADR-007: GET /users/{userId} 미참여 유저 응답 정책

**상황:** 리더보드에 참여한 적이 없는 유저를 조회할 때 404와 200 중 어떤 상태 코드를 반환할지 결정해야 했다.

**결정:** 200 OK와 `{ "rank": null, "score": 0 }`을 반환한다.

**근거:** 랭킹 도메인에서 "참여 이력 없음"은 오류가 아니라 비즈니스 상태다. 404는 리소스 자체가 존재하지 않음을 의미하지만 유저는 존재할 수 있다. 클라이언트는 `rank=null` 여부로 미참여 상태를 판단할 수 있으므로 오류 처리 분기가 줄어든다.

**트레이드오프:** `score=0`만으로는 실제 점수가 0점인 유저와 미참여 유저를 구분할 수 없다. `rank=null`을 함께 반환해 미참여 상태를 구분한다.

---

### ADR-008: POST /events 응답 스키마

**상황:** POST 응답에 순위와 점수를 포함할지 결정해야 했다.

**결정:** 처리 결과를 확인하는 데 필요한 `idempotencyKey`, `replayed`, `processedAt`만 반환한다.

**근거:** POST는 "이벤트 처리 확인" 책임만 가져야 한다. 이는 CQRS 패턴의 약식 적용이다. Write(Hot Path)는 처리량(Throughput) 극대화와 멱등성 보장에만 집중하고, 데이터 조회(Read)는 별도 API로 분리하여 각자의 목적에 맞게 캐싱 및 스케일링이 가능하도록 설계했다. rank/score를 POST 응답에 포함하면 replay 응답 시 최초 처리 시점의 과거 값을 반환하게 되어 클라이언트 혼란을 야기한다.

**트레이드오프:** 클라이언트가 처리 직후 순위를 확인하려면 GET 요청을 추가로 보내야 한다. 다만 랭킹 조회와 이벤트 처리는 서로 다른 주기로 발생하므로 현재 사용 시나리오에서는 문제가 되지 않는다.

---

### ADR-009: Audit Log 소비 도입 여부와 전송 계층 선택

**상태:** 확정 (2026-07-27 착수, 2026-07-28 결정)

**재검토 대상:** `17. Non-goals`에 있던 "Kafka 기반 메시지 브로커: Redis Streams만으로 감사 로그 파이프라인을 충분히 증명할 수 있음"이라는 항목이다. 당시 판단의 근거와 **결정을 재검토할 조건이 문서에 남아 있지 않았다.** 본 ADR은 그 결정을 수치와 함께 정식화하고 재검토한다.

**상황 (결정 시점 = 2026-07-27 기준):** audit stream(`lb:{leaderboardId}:events`)은 XADD로 append만 되고 **읽는 코드가 없었다.** 이 사실이 두 곳에 흔적으로 남아 있었다. 아래는 당시 관찰이며, 두 지점 모두 relay 도입 후 해소됐다(`pendingEntries`는 실제 XPENDING 건수, `lastDeliveredId`는 제거).

| 위치                                     | 내용                                                                                                                                                                         |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RedisAuditStreamStatusRepository:39`    | `pendingEntries`가 리터럴 `0L`. 이 상수가 API 응답 → `stream_pending_entries` gauge → Grafana 패널까지 노출되지만 어떤 판단의 입력도 아니다                                  |
| `RedisAuditStreamStatusRepository:33-35` | `lastDeliveredId`가 실제로는 `XREVRANGE` 결과, 즉 last-generated-id다. Redis의 `last-delivered-id`는 컨슈머 그룹 커서이므로 그룹이 없는 현재 **개념 자체가 존재하지 않는다** |

두 지점은 결함이라기보다 **소비자가 없다는 사실이 인터페이스에 드러난 것**이다. 따라서 이것만으로는 소비 도입의 근거가 되지 않는다.

**측정된 현재 값:**

| 항목                       | 값                  | 출처                                                                                                                                            |
| -------------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| audit 유입 (평균)          | 약 3.5/s            | `0. Scenario` 30만 건/일 ÷ 86,400                                                                                                               |
| audit 유입 (피크)          | 1,000/s             | `0. Scenario` 목표 TPS (시스템 전체값, 리더보드별 아님)                                                                                         |
| Stream 길이 안전 상한      | 약 8시간 유입량     | `MAXLEN ~ 100000` ÷ 30만 건/일. 평상시 보존 기간은 Consumer Group 워터마크 기반 트림에 따라 더 짧다                                            |
| 한 달 보존 시 Redis 메모리 | 약 900MB / 리더보드 | 30일 × 30만 × ~100B                                                                                                                             |
| 메모리 초과 시 거동        | **write 실패**      | `maxmemory-policy noeviction`                                                                                                                   |
| Redis 버전                 | 7.x                 | `docker-compose.yml` `redis:7`. `lag`/`entries-read`/`entries-added`는 7.0+ 필드이므로, Streams 쪽 관측 가능 범위는 이 버전 조건에서만 성립한다 |

**검토한 대안:** 도입 안 함 / 인프로세스 큐 / Redis Streams 컨슈머 그룹 / PostgreSQL 적재 / Kafka

**대안 간 구조적 차이:** 단일 writer(PG primary)와 파티션 분산(Kafka), MAXLEN 링 버퍼와 시간 기반 리텐션을 비교했다. 내구성은 저장 매체보다 복제 구성에 좌우된다. 단일 브로커 Kafka의 유실 가능 구간은 Redis AOF `everysec`과 같은 성격이다.

**선언한 가정:** 본 결정은 아래 두 가정 위에 선다. `0. Scenario`가 DAU 50만을 선언하고 거기서 목표 TPS를 역산한 것과 같은 방식이며, 실측이 아니라 **설계 기준**이다.

- **가정 1: 보관 요구는 30일이다.** 유저 단위 점수 추이를 30일 범위에서 조회할 수 있어야 한다.
- **가정 2: 소비자는 하나로 끝나지 않는다.** 최소 두 종류를 예정한다. **추이 집계**는 과거 데이터를 대상으로 하며 지연을 허용한다. **이상 탐지**는 현재 데이터를 대상으로 하므로 지연되면 효용이 줄어든다. 둘은 같은 로그를 서로 다른 위치에서 서로 다른 속도로 읽으며, 탐지 규칙을 변경하면 **한쪽만 과거 구간을 되감아 재평가**해야 한다.

**각 가정이 탈락시키는 것:**

| 대안                      | 탈락 사유                                                                                                                                                                                                                                    |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 인프로세스 큐             | 회수 메커니즘이 없다. 처리 중 프로세스가 종료되면 해당 일감이 소멸한다.                                                                                                                                                                     |
| Redis Streams 컨슈머 그룹 | **가정 1.** 보관 정책을 기간과 용량 양쪽으로 고정할 수 없다. `MAXLEN`(건수)은 보관 기간이 유입률에 반비례해 정책으로 선언할 수 없고, `MINID`(시간)로 30일을 걸면 리더보드당 약 900MB가 필요해 `noeviction` 정책과 만나 write 실패로 이어진다 |
| PostgreSQL 적재           | **가정 2.** 30일 900만 행 자체는 파티셔닝으로 일상적 규모이나, 복수 소비자의 오프셋·체크포인트·재처리 되감기를 애플리케이션이 직접 구현해야 한다                                                                                             |
| 도입 안 함                | 가정 1과 2를 모두 충족하지 못한다                                                                                                                                                                                                            |

**결정:** Kafka를 audit log 전송 계층으로 채택한다. 리텐션은 `retention.ms`(30일)와 `retention.bytes`를 병기해 기간과 용량을 함께 고정한다. 소비는 목적별 컨슈머 그룹으로 분리하고, 오프셋은 처리 완료 후 수동 커밋한다.

이 결정은 `17. Non-goals`의 "Kafka 기반 메시지 브로커" 제외를 **대체한다.** 당시 판단은 소비자가 없고 최근 N건 추적으로 충분하다는 전제 위에 있었으며, 위 두 가정이 그 전제를 무효화한다.

**트레이드오프:**

- **조회 계층이 추가된다.** Kafka는 로그이지 데이터베이스가 아니다. "유저 X의 30일 이벤트"를 뽑으려면 순차 재소비이거나 별도 조회 계층이 필요하다.
- **구성 요소가 늘어난다.** 현재 6개 서비스에 브로커가 추가된다. 복제까지 세우면 증가폭이 더 커진다.
- **새로운 실패 유형이 여러 개 생긴다.** 컨슈머 리밸런싱, ISR 축소, 파티션 편중, 컨슈머 랙. 도입 비용은 설치가 아니라 새로 알아야 할 장애 유형의 수다.
- **현 규모 대비 과잉이다.** 평균 유입 3.5/s는 Kafka의 설계 지점보다 네 자릿수 아래다.
- **Redis Streams 아웃박스가 추가된다.** Kafka producer는 Lua 블록 안에 들어갈 수 없으므로 Lua의 XADD를 유지하고, 별도 릴레이가 Kafka로 전달한다. 이 결정으로 ADR-002의 원자성 범위는 유지되지만 릴레이 운영 비용이 생긴다.

**관련 문서:** 구현 스펙과 검증 항목은 [SPIKE-001](SPIKE-001-kafka-migration-path.md)에서 다룬다.

---

## 20. 핵심 설계 요약

- **TPS 목표 1,000**: 비즈니스 시나리오(DAU 50만, 이벤트 집중 2시간)에서 역산한 수치 기반
- **SLO 명시**: Write p99 < 50ms, Snapshot Lag < 45s, 중복 처리 오염 0건
- **Hot/Cold Path 분리**: Redis(실시간) / PostgreSQL(기록/운영) 명확한 책임 분리
- **Competition Ranking**: ZCOUNT 기반 1,2,2,4 방식, 단순 ZREVRANK+1 사용 금지
- **Idempotency 완전 명세**: TTL 내 중복(200), payload 불일치(409), TTL 이후(신규) 3케이스 모두 정의
- **계층별 복구 범위 명시**: AOF 최대 약 1초 / RDB 최대 약 1시간 / PostgreSQL Snapshot은 Top-1,000 기준 최대 약 30초, DB 전체 백업은 현재 미구현
- **Snapshot Overwrite Guard**: Cold Start 직후 빈 데이터로 덮어쓰기 원천 차단
- **Lua Script 원자성**: Audit Log(XADD) 포함 4연산 원자적 처리
- **Kafka 감사 로그 파이프라인**: Redis Streams 아웃박스 → Kafka → PostgreSQL 원본 적재, DB 고유 제약으로 중복 저장 방지
- **Circuit Breaker**: Redis SPOF를 Fail Fast + 503 + Retry-After로 안전하게 처리
- **ADR 기반 의사결정 기록**: 모든 핵심 선택에 상황/결정/근거/트레이드오프 명시
- **k6 Tier 0 테스트 4종**: 결과 수치로 선언한 SLO와 장애 거동을 검증

---

## 요약

고빈도 이벤트 처리 환경에서 Redis 기반 실시간 처리, Kafka 기반 감사 이벤트 전달, PostgreSQL 기반 영속 저장을 분리해 성능, 정합성, 운영 안정성을 함께 설계한 백엔드 시스템이다.

비즈니스 시나리오(DAU 50만)에서 역산한 **목표 TPS 1,000**과 **명시적 SLO(p99 < 50ms, Snapshot Lag < 45s)**를 기준으로 k6 부하 테스트 결과를 수치로 검증한다. Competition Ranking 계산식, 계층별 RPO, Idempotency 3케이스 명세, Snapshot Overwrite Guard, POST 응답 스키마 설계 근거를 문서에 함께 정리한다.
