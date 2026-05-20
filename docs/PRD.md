# Realtime Ranking & Event Processing API

랭킹 및 실시간 이벤트 집계에 특화된 REST API 서비스.

Redis 기반 **Hot Path**와 PostgreSQL 기반 **Cold Path**를 분리하여 고빈도 Write 트래픽을 안정적으로 처리하는 백엔드 시스템.

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

| 항목 | 가정값 |
| --- | --- |
| DAU | 50만 |
| 이벤트 집중 시간대 | 2시간 (푸시/프로모션 기준) |
| 유저당 평균 이벤트 액션 수 | 3회 |
| 집중 시간 내 참여 유저 비율 | 20% (10만 명) |

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
- "모든 이벤트 영구 저장"이 아니라 운영 목적의 **Snapshot/통계 중심 저장**이 현실적인 선택

### Constraints (명시적 제약/스코프)

- Strong Consistency를 목표로 하지 않고 **Eventually Consistent** 모델 채택
- 실시간 처리(Hot Path)는 Redis를 **Source of Truth**로 두고, 영속 저장(Cold Path)은 PostgreSQL로 분리
- 실시간 이벤트 로그 전체를 DB에 적재하지 않고, **Top-N snapshot + 운영 지표 중심**으로 저장
- 성능은 TPS 자랑이 아니라 **SLO(p99/오류율/회복 시간)** 기반으로 정의
- 배포/재현성: `docker compose up` 이후 **5분 내 실행/테스트 가능**해야 함
- 메시지 브로커: 1차 스코프에서 Kafka 제외. 대신 **Redis Streams**로 감사 로그(Audit Log) 파이프라인 구성

---

## 1. SLO (Service Level Objectives)

> "Production-ready"는 주장이 아니라 수치로 증명해야 한다.
>
> 아래 SLO를 기준으로 k6 부하 테스트 결과를 평가한다.

### 1.1 API SLO

| API | p50 | p99 | Error Rate | 비고 |
| --- | --- | --- | --- | --- |
| POST /events | < 10ms | < 50ms | < 0.1% | Hot Path, Redis Lua 처리 기준 |
| GET /leaderboards/{leaderboardId}/tops?offset=0&limit=50 | < 5ms | < 20ms | < 0.1% | Redis ZRANGE 기준 |
| GET /leaderboards/{leaderboardId}/users/{userId} | < 5ms | < 20ms | < 0.1% | Redis ZSCORE + ZCOUNT 기준 |

### 1.2 운영 SLO

| 지표 | 목표값 | 측정 방법 |
| --- | --- | --- |
| Snapshot Lag | < 30초 (30초 주기 기준) | `snapshot_lag_seconds` Prometheus 메트릭 |
| Snapshot 성공률 | > 99% | `snapshot_failure_total` 기준 |
| Redis 장애 시 Fail Fast 응답 | < 500ms | Circuit Breaker OPEN 상태 기준 |
| Worker 재시작 후 Lag catch-up 시간 | < 2분 | Streams consumer lag 기준 |

### 1.3 Idempotency SLO

| 지표 | 목표값 |
| --- | --- |
| 동일 키 중복 요청 시 점수 오염 | 0건 |
| 동시 100 요청 동일 키 → 정확히 1회 반영 | 100% |

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
- Structured Logging (JSON, Logback)

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
    ├──[Hot Path]──────────────────────────────────────────────────────────┐
    │       │                                                              │
    │   Lua Script (원자적 실행)                                              │
    │       ├── Idempotency Key 검사 (GET)                                  │
    │       ├── ZINCRBY (ZSET 점수 업데이트)                                  │
    │       ├── Idempotency Key 저장 (SET EX {TTL})                         │
    │       └── XADD (Redis Streams - Audit Log append)                    │
    │                                                                      │
    │   [Redis]  ←── Source of Truth                                       │
    │       ├── ZSET: lb:{leaderboardId}:z                                 │
    │       ├── STRING: lb:{leaderboardId}:idem:{eventUuid} (TTL)          │
    │       └── STREAM: lb:{leaderboardId}:events                          │
    │                                                                      │
    └──[Cold Path]─────────────────────────────────────────────────────────┘
            │
    [Snapshot Worker (Spring @Scheduled + PostgreSQL Advisory Lock)]
            │
            ├── Redis ZREVRANGE Top-1000 조회
            ├── Empty guard (데이터 0건 시 저장 금지)
            ├── PostgreSQL Upsert (snapshot_batches + snapshot_entries)
            └── snapshot_lag_seconds 갱신

    [PostgreSQL]  ←── Source of Record
        ├── projects (프로젝트 정의)
        ├── leaderboards (리더보드 정의)
        ├── api_keys (인증/quota 관리)
        ├── snapshot_batches (스냅샷 메타)
        ├── snapshot_entries (Top-1000 스냅샷 행)
        └── usage_stats (운영 지표)
```

### 핵심 의도

- 실시간 트래픽은 Redis에서 처리하여 성능/확장성 확보
- PostgreSQL은 운영/관리/리포팅을 위한 기록 저장소로 활용
- Strong Consistency 대신 Eventually Consistent 채택, **Snapshot Lag를 1급 지표로 관리**

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

### 4.2 Read Flow (랭킹 조회)

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
  └── Streams 상태: consumer group lag / pending entries
```

### 4.3 Snapshot Flow (Cold Path)

```
Snapshot Worker (30초 주기)
  │
  ├── PostgreSQL Advisory Lock 획득 시도
  │     (pg_try_advisory_lock(leaderboardId의 hashCode() long 값))
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
| --- | --- | --- |
| userA | 1000 | 1 |
| userB | 800 | 2 |
| userC | 800 | 2 |
| userD | 600 | 4 |

### 5.2 동점 내 정렬 (Tie-break)

Redis ZSET은 동일 score에 대해 member 값의 **lex 오름차순**으로 정렬한다. member = userId이므로 동점 시 userId lex 오름차순이 tie-break 기준이 된다. 별도 "먼저 등록 순" tie-break는 구현하지 않는다.

### 5.3 deltaScore 정책

| 항목 | 정책 |
| --- | --- |
| 허용 범위 | 양의 정수만 허용 (1 이상) |
| 음수 delta | 허용하지 않음. 랭킹의 단조 증가(monotonic increase) 보장 |
| 최대값 | Long 범위 내 (Redis ZINCRBY는 double 처리, 정수 범위 내 사용) |
| 소수점 | 허용하지 않음 (integer only) |
| 위반 시 응답 | 400 Bad Request + `INVALID_DELTA_SCORE` |

---

## 6. Redis Key 네이밍 규칙

모든 Redis Key는 `{leaderboardId}`를 hash tag로 사용하여 Redis Cluster 환경에서 동일 슬롯에 배치되도록 설계한다.

| 용도 | Key 형식 | 타입 | TTL |
| --- | --- | --- | --- |
| 랭킹 ZSET | `lb:{leaderboardId}:z` | ZSET | 없음 |
| Idempotency Key | `lb:{leaderboardId}:idem:{eventUuid}` | STRING | 24시간 |
| Audit Log Stream | `lb:{leaderboardId}:events` | STREAM | 없음 |
| Rate Limit Counter | `rl:{apiKeyId}:{windowStart}` | STRING | windowTTL |

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
-- ARGV[4] = payloadHash  (userId + deltaScore 해시값, 409 검사용)

-- 1. 멱등키 중복 검사
local existing = redis.call('GET', KEYS[1])
if existing then
    -- storedPayloadHash를 반환. Java Layer에서 incomingPayloadHash와 비교하여
    -- 일치 → 200 replayed:true / 불일치 → 409 IDEMPOTENCY_KEY_REUSE_MISMATCH 결정
    return {0, existing}
end

-- 2. ZSET 점수 업데이트
local newScore = redis.call('ZINCRBY', KEYS[2], tonumber(ARGV[2]), ARGV[1])

-- 3. 멱등키 저장 (TTL 적용, payloadHash 저장)
redis.call('SET', KEYS[1], ARGV[4], 'EX', tonumber(ARGV[3]))

-- 4. Audit Log Streams 기록 (score 반영과 원자적으로 처리)
-- MAXLEN ~ 100000: 최근 10만 건만 유지하는 Ring Buffer 방식. Consumer 없이 XADD만 지속하면
-- Redis 메모리(maxmemory 256MB)가 소진되는 OOM 위험을 방지한다. '~'(근사치)는 성능 최적화 옵션.
redis.call('XADD', KEYS[3], 'MAXLEN', '~', 100000, '*', 'userId', ARGV[1], 'delta', ARGV[2])

return {1, newScore}  -- {isNew=true, newScore}
```

### 7.2 Lua 제약 조건

| 항목 | 규칙 | 이유 |
| --- | --- | --- |
| 연산 복잡도 | O(1) 또는 O(log N) 이하만 허용 | Redis 단일 스레드 블로킹 방지 |
| 반복문 | 금지 (상수 횟수 연산만) | p99 latency 보호 |
| KEYS 수 | 최대 3개 (모두 `{leaderboardId}` hash tag 적용) | Redis Cluster 키 슬롯 일치 요구사항 대비 |
| 모니터링 | `redis_lua_duration_ms` 메트릭 + Redis slowlog | Lua 블로킹 조기 감지 |

---

## 8. Snapshot Worker 설계

### 8.1 동시성 제어 (PostgreSQL Advisory Lock)

단일 인스턴스에서도, 다중 인스턴스에서도 중복 스냅샷이 발생하지 않아야 한다. Redis와 독립적으로 동작하여 Cold Path가 Hot Path 장애에 영향받지 않는다.

```
Lock 구현: PostgreSQL pg_try_advisory_lock(lockKey)
Lock Key: leaderboardId 문자열의 hashCode() 반환 값 (long)

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

| 계층 | 설정 | RPO (최대 유실) | 역할 |
| --- | --- | --- | --- |
| AOF | everysec | 최대 1초 | Redis crash 시 1차 복구 |
| RDB | 12시간 주기 | 최대 12시간 | AOF 손상 시 fallback |
| PostgreSQL Snapshot | 30초 주기 | 최대 30초 | Redis + AOF 모두 유실 시 fallback |
| DB Full Backup | 1시간 주기 | 최대 1시간 | DB 재난 시 최종 복구 수단 |

> **운영 기준 RPO**: 정상 운영 시 AOF everysec에 의해 최대 1초 유실. Redis + AOF 동시 유실이라는 재난 시나리오에서도 PostgreSQL Snapshot 기준 최대 30초 유실로 제한.

### Cold Start 복구 플로우

Redis 재시작 후 ZSET이 비어 있는 상태를 감지하면 다음 순서로 복구한다.

```
1. Redis 재시작 → AOF 복구 시도 (everysec 기준, 최대 1초 유실)
   └── AOF 복구 성공 → 정상 운영 재개

2. AOF 손상 시 → RDB fallback (최대 12시간 유실)

3. RDB도 유실 시 → PostgreSQL 최신 snapshot 기준 복구
   └── 복구 대상: snapshot_batches에서 leaderboard별 최신 snapshot_at 1건을 찾고,
                 해당 snapshot_id의 snapshot_entries Top-1000을 복구
   └── 복구 방법: ZADD lb:{leaderboardId}:z {score} {userId} 배치 실행
   └── 복구 완료 판단: ZCARD lb:{leaderboardId}:z > 0 확인
   └── 복구 중 Write 처리: Circuit Breaker 수동 OPEN으로 Write 차단 (데이터 오염 방지)
   └── 복구 완료 후 Circuit Breaker 정상화 → Write 재개
```

---

## 10. Redis SPOF 대응 전략

### 10.1 장애 시나리오별 대응

| 시나리오 | 대응 방식 | 비고 |
| --- | --- | --- |
| Redis 일시 응답 지연 | Circuit Breaker (Closed → Half-Open → Open) | Resilience4j 활용 |
| Redis 완전 장애 | Circuit Breaker OPEN → 503 즉시 반환 | Write 요청 차단으로 데이터 오염 방지 |
| Redis 재시작 | AOF Persistence로 데이터 복구 (최대 1초 유실) | AOF fsync: everysec |
| Redis + AOF 동시 유실 | PostgreSQL Snapshot 기준 Cold Start 복구 | 최대 30초 유실 |
| Snapshot Worker 실패 | 재시도 3회 후 알림, 다음 주기 재개 | Cold Path 장애는 Hot Path와 독립 |

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

> **Fixed Window vs Sliding Window**: 현 구현은 `rl:{apiKeyId}:{windowStart}` 키에 INCR/EXPIRE를 적용하는 **Fixed Window** 방식이다. 윈도우 경계에서 최대 2배까지 버스트가 발생할 수 있지만, 본 프로젝트 범위에서 구현 복잡도 대비 효과가 충분한 이유로 Fixed Window를 선택한다.

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

| 정책 | 기준 |
| --- | --- |
| 기본 Rate Limit | API Key당 초당 100 요청 |
| 일일 Quota | API Key당 100만 요청 |
| 초과 응답 | HTTP 429 + `X-RateLimit-Remaining`, `Retry-After` 헤더 |
| 악성 트래픽 차단 효과 | T7 테스트에서 attackKey vs normalKey 격리 증명 |

---

## 12. Idempotency 정책

### 12.1 TTL 기준 처리 정책

| 상황 | 처리 방식 | 응답 |
| --- | --- | --- |
| TTL 내 동일 Key + 동일 payload | 중복으로 간주, 점수 미반영 | 200 OK + `replayed: true` |
| TTL 내 동일 Key + **다른 payload** | 재사용 오류 | 409 Conflict + `IDEMPOTENCY_KEY_REUSE_MISMATCH` |
| TTL 이후 동일 Key 재요청 | 신규 요청으로 간주, 점수 반영 | 200 OK + `replayed: false` |
| TTL 경계 레이스 컨디션 | Lua Script 원자성으로 처리 | 별도 보호 불필요 |

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
- Redis Streams를 통한 Audit Log 파이프라인 (score 반영과 원자적 기록, 운영 추적용)
  - XADD MAXLEN ~ 100000 적용: 최근 10만 건만 유지하는 Ring Buffer 방식으로 OOM 방지
  - Consumer Group은 구현하지 않으며, XLEN/XRANGE로 운영자가 직접 이벤트 흐름을 추적하는 Audit 용도로 사용

### 13.2 PostgreSQL (Cold Path – Source of Record)

- 프로젝트/리더보드 정의 및 설정
- API Key 관리 (인증/quota)
- 사용량 통계 저장
- 랭킹 스냅샷 (Top-1000, 30초 주기)

---

## 14. Snapshot & Data Retention Policy

### 스냅샷 정책

- 실시간 전체 랭킹은 PostgreSQL에 저장하지 않음
- 스냅샷 범위는 **Top-1,000만** 허용 (전체 스캔 금지)
- 기본 주기: 30초 (운영 목표에 따라 조정 가능)
- Empty Guard 적용: Redis ZSET이 비어 있는 경우 저장 금지

### Snapshot Lag 관리

| 주기 | 예상 Lag | DB 부하 | 적합한 상황 |
| --- | --- | --- | --- |
| 10초 | < 15초 | 높음 | 라이브 이벤트, 실시간성 중요 |
| 30초 | < 45초 | 중간 | 기본값 (본 프로젝트 기준) |
| 5분 | < 6분 | 낮음 | 일반 운영, 비용 절감 |

T8 테스트에서 주기 2종(30초 vs 5분) 각각에서 Mixed Workload 실행, lag/latency trade-off를 수치화한다.

---

## 15. Observability (운영 관측)

### Metrics (Prometheus)

**HTTP Layer**

- `http_request_duration_seconds` (endpoint, method, status 라벨)
- `http_requests_total`

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

- `stream_pending_entries`: 현재 구현에서는 dedicated consumer group이 없어 항상 `0`
- `stream_consumer_lag`: 현재 구현에서는 consumer group lag 대신 audit stream length 근사치

### Logs (Structured JSON)

모든 로그는 JSON 포맷으로 출력한다. 공통 필드:

```json
{
  "timestamp": "ISO8601",
  "level": "INFO|WARN|ERROR",
  "requestId": "uuid",
  "apiKeyId": "key_xxx",
  "leaderboardId": "lb_xxx",
  "userId": "user_xxx",
  "idempotencyKey": "idem_xxx",
  "event": "EVENT_NAME",
  "durationMs": 12,
  "result": "SUCCESS|DUPLICATE|CONFLICT|RATE_LIMITED|CIRCUIT_OPEN"
}
```

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
  → { "pendingEntries": number, "consumerLag": number, "lastDeliveredId": "string" }

GET /internal/circuit-breaker/status
  → { "state": "CLOSED|HALF_OPEN|OPEN", "failureRate": number }
```

> **현재 구현 메모**: `/internal/streams/status`는 dedicated consumer group 없이 audit stream만 기록한다. 따라서 `pendingEntries=0`, `consumerLag=stream length` 근사치로 해석한다.

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

| 항목 | 제외 이유 |
| --- | --- |
| WebSocket 기반 실시간 Push | 복잡도 대비 본 프로젝트 범위 밖 |
| 모든 이벤트의 영구 보관 | 운영 비용 및 스토리지 현실적 제약 |
| Strong Consistency 보장 | Eventually Consistent 선택과 트레이드오프 관계 |
| Redis Sentinel / Cluster | 본 프로젝트 범위 외, 향후 확장 방향만 문서화 |
| Kafka 기반 메시지 브로커 | Redis Streams로 감사 로그 파이프라인 충분히 증명 가능 |
| Streams Replay 복구 | Cold Start 복구는 PostgreSQL Snapshot 기준으로 충분 |
| Top API Cursor Pagination | offset 기반 + 상한 제한으로 운영 범위 충분 |

---

## 18. Benchmark & Reliability Test Plan (k6)

### 18.0 목적

이 섹션은 "성능 자랑"이 아니라 아래 3가지를 증명하기 위한 계획이다.

- Production-ready인가? (SLO 달성 여부, 오류율, 회복 가능성)
- 이 시스템의 핵심 리스크(중복/편향/폭주/스냅샷 지연)를 이해했는가?
- 측정 결과로 트레이드오프를 설명할 수 있는가?

### 18.1 테스트 환경 (재현 가능성 보장)

| 항목 | 사양 |
| --- | --- |
| 테스트 실행 환경 | Docker Compose (로컬 Mac M-series) |
| API Server | Spring Boot, JVM heap 512MB |
| Redis | 7.x, AOF everysec, maxmemory 256MB |
| PostgreSQL | 15.x, max_connections 100 |
| k6 실행 | 로컬 동일 머신 (네트워크 오버헤드 배제 목적) |

> ⚠️ 로컬 환경 수치임을 결과 보고서에 명시. 절대값보다 SLO 달성 여부와 병목 분석이 핵심.

### 18.2 테스트 Tier 정의

**Tier 0 (Must-have, 결과 공개 필수)**

| 테스트 | 목적 | 핵심 검증 지표 |
| --- | --- | --- |
| T1: Hot Path Write Throughput | 1,000 TPS 목표 달성 여부 | p99 < 50ms, error rate < 0.1% |
| T3: Mixed Workload (Write+Read) | Read SLO 보호 여부 | Read p99 < 20ms (Write 부하 중) |
| T4: Idempotency Correctness | 동시 중복 요청 정합성 | 점수 오염 0건, 200 OK 정상 반환 |
| T8: Snapshot Pipeline Impact | Snapshot lag/latency trade-off | lag < 30초, Write p99 영향 < 10% |

**Tier 1 (Differentiator)**

| 테스트 | 목적 |
| --- | --- |
| T5: Hot Key / Skew Test | 편향 트래픽에서 p99 보호 여부 |
| T6: Spike / Burst Resilience | 급격한 트래픽 증가 시 Circuit Breaker 동작 확인 |
| T7: Rate Limit Enforcement | normalKey vs attackKey SLO 격리 증명 |

**Tier 2 (Optional)**

| 테스트 | 목적 |
| --- | --- |
| T2: Read Performance (limit별) | Top-100 vs Top-1000 응답시간 비교 |
| T9: Soak Test (30분+) | TTL 만료/메모리 드리프트/Lag 장시간 안정성 |

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

| TPS | p50 | p95 | p99 | Error Rate |
|-----|-----|-----|-----|------------|
| 100 | Xms | Xms | Xms | X% |
| 500 | Xms | Xms | Xms | X% |
| 1000 | Xms | Xms | Xms | X% |
| 1500 | Xms | Xms | Xms | X% |

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

**상황:** 고빈도 Write 트래픽 처리 시 PostgreSQL 직접 반영 vs Redis 중간 레이어 선택 필요

**결정:** Redis ZSET을 실시간 랭킹 Source of Truth로 채택

**근거:** PostgreSQL Row Lock 경쟁이 목표 TPS(1,000)에서 병목이 됨. Redis ZSET의 O(log N) 복잡도와 단일 스레드 특성이 고빈도 Write에 최적

**트레이드오프:** Redis 장애 시 Write 기능 전체 중단. AOF + Circuit Breaker + PostgreSQL Snapshot으로 위험 완화

---

### ADR-002: Lua Script 원자성 범위 결정

**상황:** Idempotency 체크 + ZINCRBY + Key 저장 + 이벤트 기록을 원자적으로 처리 필요

**결정:** 4개 연산 모두 단일 Lua Script에 포함 (Idempotency GET + ZINCRBY + SET EX + XADD)

**근거:** score 반영과 Audit Log 기록이 원자적으로 함께 동작해야 함. score는 반영됐는데 Streams에 누락되는 경우를 제거. XADD는 O(1) 상수 시간 연산으로 Lua 블로킹 리스크 없음

**트레이드오프:** Lua Script에 KEYS 3개 필요. Redis Cluster 환경에서 `{leaderboardId}` hash tag 통일이 선행 조건

---

### ADR-003: Snapshot Worker 분산 락 구현 방식

**상황:** 다중 인스턴스 배포 시 Snapshot Worker 중복 실행 방지 필요. Redis 기반 SET NX EX vs PostgreSQL Advisory Lock 선택 필요

**결정:** PostgreSQL pg_try_advisory_lock 기반 분산 락 구현

**근거:** Cold Path(Snapshot Worker)는 Redis 장애와 독립적으로 동작해야 한다. Redis SET NX EX 방식은 Hot Path 장애 시 Lock 획득 자체가 불가능해 Cold Path까지 연쇄 중단되는 리스크 존재. PostgreSQL Advisory Lock은 세션 종료 시 자동 해제되므로 Worker 크래시 시에도 Lock 해제 보장. TTL/clock skew 리스크 없음

**트레이드오프:** PostgreSQL 커넥션 소비. 단, Snapshot Worker는 주기 실행(30초 1회)이므로 커넥션 점유 시간 미미. Lock 키는 leaderboardId의 hashCode() long 값 사용

---

### ADR-004: Rate Limit 구현 방식

**상황:** API Key 기반 Rate Limit 구현 시 Redis Lua vs Bucket4j, Fixed Window vs Sliding Window 선택

**결정:** Redis Lua Script 기반 Fixed Window Counter 직접 구현

**근거:** Redis는 이미 Hot Path에서 필수 의존 컴포넌트. Bucket4j 등 외부 라이브러리 도입 시 Redis와의 분리 비용 발생. Sliding Window는 시간대별 레코드가 필요해 구현 복잡도 상승. Fixed Window(INCR/EXPIRE)은 Lua 원자성으로 단순하게 구현 가능하며 본 프로젝트 범위에서 운영 비용 대비 효과가 충분함

**트레이드오프:** 윈도우 경계에서 최대 2배 버스트 발생 가능(앞 50% + 뒤 50% 포함). Redis 장애 시 Rate Limit도 동시 중단되지만 Circuit Breaker가 이미 Redis 장애를 처리하므로 허용

---

### ADR-005: Redis SPOF 위험 완화 수준

**상황:** Redis 단일 노드 장애가 서비스 전체에 영향

**결정:** Redis Sentinel/Cluster는 본 프로젝트 범위 외. Circuit Breaker + AOF + PostgreSQL Snapshot으로 완화

**근거:** 본 프로젝트 목적상 HA 인프라 설정보다 장애 시 거동과 회복 패턴 증명이 우선

**트레이드오프:** Redis 장애 시 Write 기능 중단 피할 수 없음. 향후 Sentinel 추가로 해결 가능

---

### ADR-006: Competition Ranking 방식 선택

**상황:** ZREVRANK+1 단순 방식 vs Competition Ranking(1,2,2,4) 방식 선택 필요

**결정:** `rank = ZCOUNT(key, ({myScore}, +inf]) + 1` 방식 채택

**근거:** ZREVRANK+1은 동점자를 서로 다른 순위로 반환하여 "같은 점수면 같은 순위"라는 사용자 기대를 위반한다. 게임/랭킹 도메인에서 Competition Ranking이 표준이며, ZCOUNT는 O(log N) 연산으로 성능 허용 범위 내

**트레이드오프:** 조회 시 ZSCORE + ZCOUNT 2회 연산 필요 (2 RTT). 단, GET /users/{userId}는 실시간 조회이므로 현재 SLO(p99 < 20ms) 범위 내에서 허용. Top API는 ZREVRANGE 결과에 순위를 순차 부여(별도 ZCOUNT 불필요). Read TPS가 임계점을 초과할 경우 두 연산을 단일 Lua Script 또는 Redis Pipeline으로 묶어 1 RTT로 개선 가능.

---

### ADR-007: GET /users/{userId} 미참여 유저 응답 정책

**상황:** 리더보드에 참여 이력이 없는 유저 조회 시 404 vs 200 선택 필요

**결정:** 200 OK + `{ "rank": null, "score": 0 }` 반환

**근거:** 랭킹 도메인에서 "참여 이력 없음"은 에러 상태가 아니라 비즈니스 상태다. 404는 리소스 자체가 존재하지 않음을 의미하지만 유저 자체는 존재할 수 있다. 클라이언트가 null 체크로 미참여 여부를 처리할 수 있어 에러 핸들링 분기가 줄어든다

**트레이드오프:** score=0이 실제 점수 0점과 구분되지 않을 수 있음. rank=null로 미참여를 명확히 구분하여 해결

---

### ADR-008: POST /events 응답 스키마

**상황:** POST 응답에 rank/score 포함 여부 결정 필요

**결정:** 처리 확인 중심 응답만 반환 (`idempotencyKey`, `replayed`, `processedAt`)

**근거:** POST는 "이벤트 처리 확인" 책임만 가져야 한다. 이는 CQRS 패턴의 약식 적용이다. Write(Hot Path)는 처리량(Throughput) 극대화와 멱등성 보장에만 집중하고, 데이터 조회(Read)는 별도 API로 분리하여 각자의 목적에 맞게 캐싱 및 스케일링이 가능하도록 설계했다. rank/score를 POST 응답에 포함하면 replay 응답 시 최초 처리 시점의 과거 값을 반환하게 되어 클라이언트 혼란을 야기한다.

**트레이드오프:** 클라이언트가 처리 후 즉시 순위를 확인하려면 추가 GET 요청 필요. 단, 랭킹 조회와 이벤트 처리는 서로 다른 주기로 발생하므로 실용적 문제 없음

---

## 20. 핵심 설계 요약

- **TPS 목표 1,000**: 비즈니스 시나리오(DAU 50만, 이벤트 집중 2시간)에서 역산한 수치 기반
- **SLO 명시**: Write p99 < 50ms, Snapshot Lag < 30s, 중복 처리 오염 0건
- **Hot/Cold Path 분리**: Redis(실시간) / PostgreSQL(기록/운영) 명확한 책임 분리
- **Competition Ranking**: ZCOUNT 기반 1,2,2,4 방식, 단순 ZREVRANK+1 사용 금지
- **Idempotency 완전 명세**: TTL 내 중복(200), payload 불일치(409), TTL 이후(신규) 3케이스 모두 정의
- **계층별 RPO 명시**: AOF 1초 / Snapshot 30초 / DB Backup 1시간, 각 계층 역할 명확
- **Snapshot Overwrite Guard**: Cold Start 직후 빈 데이터로 덮어쓰기 원천 차단
- **Lua Script 원자성**: Audit Log(XADD) 포함 4연산 원자적 처리
- **Circuit Breaker**: Redis SPOF를 Fail Fast + 503 + Retry-After로 안전하게 처리
- **ADR 기반 의사결정 기록**: 모든 핵심 선택에 상황/결정/근거/트레이드오프 명시
- **k6 Tier 0 테스트 4종**: 결과 수치로 Production-ready를 증명

---

## 요약

고빈도 이벤트 처리 환경에서 Redis 기반 실시간 처리와 PostgreSQL 기반 영속 저장을 분리해 성능, 정합성, 운영 안정성을 함께 설계한 백엔드 시스템이다.

비즈니스 시나리오(DAU 50만)에서 역산한 **목표 TPS 1,000**과 **명시적 SLO(p99 < 50ms, Snapshot Lag < 30s)**를 기준으로 k6 부하 테스트 결과를 수치로 검증한다. Competition Ranking 계산식, 계층별 RPO, Idempotency 3케이스 명세, Snapshot Overwrite Guard, POST 응답 스키마 설계 근거를 문서에 함께 정리한다.
