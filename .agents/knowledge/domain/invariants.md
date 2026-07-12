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

### AI callback

- AI는 event suggestion INSERT와 source association UPDATE를 하나의 staging transaction으로 commit한 뒤 알린다.
- callback body는 `status`, `errorCode`, `error`뿐이며 event나 `itemIds`를 전달하지 않는다.
- 서버가 staging relation을 읽어 내부 `TimelineEventSuggestionDto.itemIds`를 조립하고 검증한다.
- raw callback token은 dispatch 때 AI에만 전달한다. Redis에는 SHA-256 hash를 저장한다.
- callback token은 constant-time 비교 후 허용 횟수를 atomic하게 소비한다.
- `PROCESSING` TTL은 1시간, terminal task TTL은 24시간이며 staging retention은 7일이다.

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

- DRAFT→SAVED 사용자 전이, emotion·memo 입력 API가 없다.
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
