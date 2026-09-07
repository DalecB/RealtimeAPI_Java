# Kafka 배치 처리와 DLT 변경 노트

## 결론

기존 컨슈머는 파싱할 수 없는 레코드를 로그만 남기고 버렸다. 지금은 실패 레코드만 3초 간격으로 총 3회 처리한 뒤 `lb-audit-events.DLT`로 보내고, 같은 배치의 정상 레코드는 PostgreSQL에 저장한다.

`spring.kafka.consumer.max-poll-records=500`은 처리 시작 조건이 아니라 한 번의 poll이 가져올 수 있는 최대 개수다. 500건이 쌓이지 않아도 현재 도착한 레코드는 처리된다. 현재 코드에는 별도의 5분 타이머가 없다.

## 전후 비교

| 항목 | 기존 | 변경 후 |
| --- | --- | --- |
| 파싱 실패 | 로그만 남기고 건너뜀 | 실패 레코드만 총 3회 처리 후 DLT로 이동 |
| 정상 레코드 | PostgreSQL 저장 | 실패 레코드가 섞여 있어도 PostgreSQL 저장 |
| 입력 검증 | 누락·잘못된 숫자가 기본값으로 바뀔 수 있었음 | 필수 필드, UUID, 숫자, 이벤트 타입을 명시적으로 검증 |
| DLT 발행 실패 | 해당 없음 | 예외를 전파해 원본 배치 오프셋 커밋을 막음 |
| DLT 토픽 | 없음 | `lb-audit-events.DLT`, 원본과 같은 3개 파티션·30일/파티션당 1GB 보관 |

## 실제 처리 흐름

```text
Kafka: lb-audit-events
          │
          │ poll (최대 500건, 500건 미만도 반환)
          ▼
AuditEventConsumer
          │
          ├─ 1차 파싱 ────────────────┐
          │                           │
          │ 정상                      │ 실패
          │                           ▼
          │                    3초 후 2차 파싱
          │                           │
          │                    3초 후 3차 파싱
          │                           │
          ▼                           ▼
PostgreSQL 저장                 여전히 실패한 레코드
                                      │
                                      ▼
                              lb-audit-events.DLT
                                      │
                                      ▼
                         리스너 정상 반환 → 배치 오프셋 커밋
```

처리 순서는 `파싱과 재시도 → 정상 행 DB 저장 → 최종 실패 레코드 DLT 발행 → 오프셋 커밋`이다.

## 예시

한 poll에 다음 세 건이 들어왔다고 가정한다.

```text
A: JSON 깨짐
B: 정상
C: idempotencyKey가 UUID가 아님
```

1. 1차 처리에서 B는 정상 목록에 들어가고 A, C는 실패 목록에 들어간다.
2. 3초 뒤 A, C만 다시 처리한다.
3. 다시 3초 뒤 남은 실패 레코드만 세 번째로 처리한다.
4. B를 `audit_events`에 저장한다.
5. 끝까지 실패한 A, C를 각각 DLT에 발행한다.
6. DB 저장과 DLT 발행이 모두 성공하면 원본 배치 오프셋을 커밋한다.

따라서 500건이 모두 실패해도 레코드마다 6초씩 기다리지 않는다. 실패 목록 전체를 배치로 재검증하므로 추가 대기는 총 6초다.

## 검증하는 값

다음 값은 이제 잘못되면 파싱 실패로 취급한다.

- Kafka key인 `leaderboardId`: UUID
- `eventId`: 필수이며 앞부분이 epoch milliseconds
- `type`: `new` 또는 `conflict`
- `userId`, `delta`, `apiKeyId`: 필수 숫자
- `idempotencyKey`: UUID

DLT 레코드에는 원본 key/value와 원본 topic 등 Spring Kafka의 원본 메타데이터 헤더가 붙는다. 현재 통합 테스트는 key, value, 원본 topic 헤더를 확인한다.

## 실패 시 거동

### PostgreSQL 저장 실패

DB 저장에서 예외가 발생하면 DLT 발행 단계까지 가지 못하고 리스너가 실패한다. 이번 변경에는 PostgreSQL 장애용 장기 재시도 정책이 포함되지 않았다.

### DLT 발행 실패

`setFailIfSendResultIsError(true)` 때문에 DLT 발행 실패가 호출자에게 전파된다. 리스너가 정상 반환하지 않으므로 원본 배치 오프셋은 커밋되지 않는다. 앞서 저장된 정상 행이 다시 처리될 수 있지만 `(leaderboard_id, event_id)` UNIQUE와 `ON CONFLICT DO NOTHING`이 중복 저장을 막는다.

### 일부 DLT만 발행한 뒤 프로세스 종료

배치 오프셋 커밋 전에 종료되면 원본 배치가 다시 처리된다. 이미 DLT에 발행된 레코드가 중복으로 들어갈 수 있다. 현재 DLT는 exactly-once가 아니라 at-least-once다.

## 변경 파일 역할

- `AuditEventConsumer`: 검증, 실패 레코드 재처리, 정상 행 저장, DLT 전달
- `AuditTopicConfig`: DLT 토픽과 `DeadLetterPublishingRecoverer` 생성
- `application.properties`: 재시도 간격 3초 설정
- `AuditEventConsumerTest`: 총 3회 처리와 DLT 실패 예외 전파 검증
- `KafkaAuditRoundTripTest`: 실제 Kafka/PostgreSQL에서 정상 저장, DLT 발행, 오프셋 커밋 검증
- `README.md`, `PRD.md`, `SPIKE-001-kafka-migration-path.md`: 구현 완료 상태와 남은 범위 반영

## 아직 하지 않은 것

- PostgreSQL 일시 장애에 대한 장기 backoff
- DLT 중복 방지
- DLT 재처리 도구 또는 운영 API
- DLT 적재량 알림과 최근 실패 원문 조회

현재 범위에서는 처리 불가 레코드가 정상 레코드를 영구 차단하거나 조용히 유실되지 않게 만드는 데까지만 구현했다.
