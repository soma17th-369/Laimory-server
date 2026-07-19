# Domain Invariants

## Scope

timeline draft, 저장, 사진과 인증 흐름에서 반드시 보존해야 하는 규칙을 모은다.

## Read When

timeline·auth·persistence use case, schema, Redis TTL, callback 또는 cleanup을 바꿀 때 읽는다.

## Authoritative Sources

- timeline/auth/user services, entities, repositories and tests
- `src/main/resources/db/schema.sql`
- `SecurityConfig`, `OpenApiConfig`
- Redis stores and cleanup schedulers

## Current Invariants

### Timeline

- `recordDate`는 정오 경계로 정하고 `(user_id, record_date)`는 유일하다.
- `SAVED` record에는 새 draft source를 append하지 않는다.
- 기존 final `rawId`와 같은 source는 제외하고 같은 request 안 중복도 한 번만 취급한다.
- 같은 날짜 append는 기존 event/item의 그룹·title·subtitle·memo를 바꾸지 않는다.
- staging association이 NULL이면 omitted source다. non-null association은 같은 task의 suggestion을 가리켜야 한다.
- 채택된 source item은 정확히 하나의 final event에 속한다.
- final `timeline_items.timeline_event_id`는 non-null이고 event 삭제에 cascade된다.
- finalize는 검증, record/event/item 저장과 두 staging table 삭제를 하나의 DB transaction으로 수행한다.
- DB commit 이후 Redis를 `SUCCESS`로 바꾼다.
- event `startAt`의 정확한 충돌은 +10분씩 미는 best-effort다. DB unique invariant는 아니다.
- event `endAt`은 조정된 start보다 앞서지 않도록 clamp한다.

### Deletion

- Event·DailyRecord 삭제는 DRAFT record에서만 허용한다. SAVED는 모든 작업 전에 거절하고
  없음·비소유는 404로 은닉한다.
- 삭제는 대상 PHOTO의 S3 배치 삭제가 **전부 성공한 후에만** DB cascade 삭제를 시작한다.
  S3 실패(`ERROR_1017`)면 DB를 보존하고, S3 성공 후 DB 실패(500)는 재시도로 수렴한다
  (이미 지워진 key는 S3가 성공 처리). Outbox·보상 업로드·참조 카운트는 두지 않는다.
- 삭제 대상 PHOTO payload가 깨졌거나 filename이 없으면 S3만 건너뛰고 행 삭제는 진행한다
  (orphan 허용 — draft cleanup과 동일 규칙).
- 날짜 guard(`timeline:date-guard:{userId}:{recordDate}`)가 같은 날짜의 draft(AI 작업)와 삭제를
  직렬화한다 — draft는 `task:{taskId}`, 삭제는 `delete:{operationId}` holder로 선점하며,
  삭제는 성공·1017·500 모든 종료 경로에서 compare-and-release한다(해제는 best-effort, TTL 1h가 안전망).
- **향후 DRAFT→SAVED 전환(save) API도 같은 날짜 guard를 취득해야 한다** — 삭제·AI 작업과
  상태 전이가 경합하지 않게 하는 직렬화 지점이다.
- 마지막 Event를 삭제해도 DailyRecord는 유지한다. 하루 전체 제거는 DailyRecord 삭제만 담당한다.
- 하위 행(events/items) 삭제는 JPA cascade가 아니라 DB FK `ON DELETE CASCADE`가 담당한다 —
  서버는 부모 행만 `deleteById`로 지운다.

### AI callback

- AI는 event suggestion INSERT와 source association UPDATE를 하나의 staging transaction으로 commit한 뒤 알린다.
- callback body는 `status`, `errorCode`, `error`뿐이며 event나 `itemIds`를 전달하지 않는다.
- 서버가 staging relation을 읽어 내부 `TimelineEventSuggestionDto.itemIds`를 조립하고 검증한다.
- raw callback token은 dispatch 때 AI에만 전달한다. Redis에는 SHA-256 hash를 저장한다.
- callback token은 constant-time 비교 후 허용 횟수를 atomic하게 소비한다.
- `PROCESSING` TTL은 1시간, terminal task TTL은 24시간이며 staging retention은 7일이다.
- `processingStartedAt`은 전처리·staging 저장 후 PROCESSING 저장 직전에 한 번 캡처하며 PROCESSING
  전용이다 — terminal 전이 시 보존하지 않고 폐기한다(terminal에 경과 시간을 제공하지 않음, TTL 불변).
- 신규 PROCESSING polling의 `elapsedSeconds`는 완료된 초이며 음수가 되지 않는다(시계 역행·future
  timestamp는 0 clamp). 시각이 없는 legacy PROCESSING task는 값을 위조하지 않고 필드를 생략한다(unknown).

### Photos

- S3 key는 서버가 userId와 filename에서 파생하며 client가 full key를 정하지 않는다.
- presigned PUT은 content type과 content length를 서명에 묶는다.
- `photoUrl`은 save 시 materialize한다. CDN domain이나 key 규칙 변경에는 기존 payload backfill이 필요하다.
- 만료 PHOTO draft는 S3 삭제에 성공한 뒤 DB row를 삭제한다. S3 실패 때 row를 남겨 retry한다.
- finalized photo와 presign 후 draft가 생기지 않은 orphan object는 현재 cleanup 범위가 아니다.

### Authentication

- 사용자는 `(provider, provider_user_id)`로만 결합하며 email로 provider account를 merge하지 않는다.
- access JWT에는 `iss/sub/iat/exp`만 두고 PII를 넣지 않는다.
- refresh token raw value는 저장하지 않고 hash만 저장한다.
- refresh rotation과 reuse detection은 transactionally 처리하고 reuse 때 그 사용자의 refresh를 모두 revoke한다.
- App Code는 hash-key Redis entry로 저장하고 GETDEL로 한 번만 소비한다.
- `/a/api`는 의도된 사용자 인증 영역이지만 현재 enforcement와 userId propagation은 미구현이다.
  이 gap이 닫히기 전 timeline은 `DEFAULT_USER_ID=0`을 사용한다.

## Known Gaps

- DRAFT→SAVED 사용자 전이, emotion 입력 API가 없다.
- production AI dispatcher, JWT request filter와 authenticated principal propagation이 없다.
- photo orphan cleanup과 automatic deployment rollback이 없다.

## Update When

위 규칙을 강제하는 schema, service, security, Redis TTL 또는 cleanup 순서가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest
```
