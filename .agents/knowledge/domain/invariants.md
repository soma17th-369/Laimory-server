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

- `recordDate`는 클라이언트 요청값을 서버 계산·보정 없이 그대로 쓰고 `(user_id, record_date)`는 유일하다.
- draft 요청의 `timelineWindow`는 필수값과 `startTime < endTime`만 검증하고, Redis에는 local 원본을
  보존하며 AI transport에는 record timezone 기반 offset ISO로 변환해 전달한다. `recordDate`·`recordAt`·
  window 상호 간 날짜 정합성은 검증하지 않는다(독립 계약).
- draft POST는 DailyRecord 선생성(find-or-create + `recordAt/recordTimezone` 갱신 + SAVED 재확인)과
  source 저장을 한 트랜잭션으로 AI dispatch 전에 커밋한다. Redis 저장 실패 시 source rows만 보상
  삭제하고 DailyRecord는 유지한다(empty DRAFT는 같은 날짜 재시도가 재사용, 자동 cleanup 없음).
- `SAVED` record에는 새 draft source를 append하지 않는다.
- 기존 final `rawId`(record의 Event→junction→Item 경로)와 같은 draft source는 제외하고 같은 request 안
  중복도 한 번만 취급한다. AI도 write 직전 같은 조건을 재검사한다(이중 방어 — DB UNIQUE 없음,
  race/legacy 중복 행 허용). Event PATCH의 PHOTO 추가는 request rawId 중복을 첫 항목 우선으로 접고,
  같은 record의 기존 PHOTO Item을 재사용하며 대상 Event에 이미 연결됐으면 no-op 처리한다. 같은 rawId의
  non-PHOTO Item이 있으면 입력 전체를 거절한다.
- 같은 날짜 append는 기존 event/item의 그룹·title·subtitle·memo를 바꾸지 않는다(append-only).
- Event↔Item 연결은 junction(`timeline_event_items`)이 유일 경로다. 한 Item은 같은 DailyRecord의 여러
  Event에 공유될 수 있고, 채택된 source 하나는 정확히 한 final Item이 된다(여러 Event 공유 시에도 1행).
- same-DailyRecord Item 공유는 DB 제약이 아니라 writer 계약이다 — AI·fake는 새 Item을 현재 task의 새
  Event에만 연결하고, Event PATCH는 같은 record의 기존 PHOTO를 대상 Event에 재사용할 수 있다.
- draft final write(Event/Item/junction 저장 + accepted source 삭제)는 AI가 하나의 DB transaction으로
  commit한다. Event PATCH의 Event/memo 수정 + 수동 PHOTO Item/junction 추가는 서버가 별도의 하나의 DB
  transaction으로 commit한다.
- AI final commit 이후에만 callback이 오고, 서버는 그때 Redis를 `SUCCESS`로 바꾼다.
- event `startAt`의 정확한 충돌은 +10분씩 미는 best-effort다(적용 주체는 AI writer). DB unique
  invariant는 아니다. event `endAt`은 조정된 start보다 앞서지 않도록 clamp한다.
- final event `eventType`은 논리적 non-null이며 미분류는 `UNKNOWN` 단일 표현이다(별도 nullable 상태 없음).
  미지원 literal은 AI validation FAILED다(새 literal 활성화 순서: Server enum 배포 → AI writer 활성화).

### Deletion

- Event·DailyRecord 삭제는 DRAFT record에서만 허용한다. SAVED는 모든 작업 전에 거절하고
  없음·비소유는 404로 은닉한다.
- 삭제는 exclusive Item(삭제 대상 Event에만 연결) PHOTO의 S3 배치 삭제가 **전부 성공한 후에만**
  DB 삭제를 시작한다. 다른 Event에도 연결된 shared Item/PHOTO는 유지한다.
  S3 실패(`-1017`)면 DB를 보존하고, S3 성공 후 DB 실패(500)는 재시도로 수렴한다
  (이미 지워진 key는 S3가 성공 처리). Outbox·보상 업로드·참조 카운트는 두지 않는다.
- 삭제 대상 PHOTO payload가 깨졌거나 filename이 없으면 S3만 건너뛰고 행 삭제는 진행한다
  (orphan 허용 — draft cleanup과 동일 규칙).
- 날짜 guard(`timeline:date-guard:{userId}:{recordDate}`)가 같은 날짜의 draft(AI 작업), 삭제와
  Event PATCH의 수동 PHOTO 추가를 직렬화한다 — draft는 `task:{taskId}`, 삭제는
  `delete:{operationId}`, PHOTO 추가 PATCH는 `patch-photo-add:{operationId}` holder로 선점한다.
  삭제와 PHOTO 추가 PATCH는 성공·실패 모든 종료 경로에서 compare-and-release한다(해제는 best-effort,
  TTL 1h가 안전망). `photosToAdd`가 없거나 빈 Event PATCH는 guard를 취득하지 않는다.
- **향후 DRAFT→SAVED 전환(save) API도 같은 날짜 guard를 취득해야 한다** — 삭제·AI 작업과
  상태 전이가 경합하지 않게 하는 직렬화 지점이다.
- 마지막 Event를 삭제해도 DailyRecord는 유지한다. 하루 전체 제거는 DailyRecord 삭제만 담당한다.
- Event/Record 행 삭제 시 자기 junction은 DB FK `ON DELETE CASCADE`가 지운다(JPA cascade 없음).
  Item은 record FK가 없어 cascade되지 않으므로 삭제 대상에만 연결된 orphan을 같은 트랜잭션에서
  명시 삭제한다 — orphan 판정은 삭제 전 junction 스냅샷 기준이다(같은 날짜 쓰기는 guard가 직렬화).

### AI callback

- AI는 final direct-write commit 이후에만 알린다(commit-then-callback).
- callback body는 `status`, `errorCode`, `error`뿐이며 결과 graph를 전달하지 않는다.
- callback `errorCode`와 Redis FAILED task `error`는 음수 JSON integer다. 문자열 코드는 허용하지 않는다.
- 서버는 callback에서 결과를 조립·검증·저장하지 않고 Redis terminal 전이만 기록한다.
- raw callback token은 dispatch body로 AI에만 전달한다. Redis에는 SHA-256 hash를 저장한다.
- callback token은 constant-time 비교 직후 Redis `SET NX` marker로 원자 소비한다. 최초 요청 하나만
  terminal 처리로 진행하고 같은 token 재사용은 401 `-1012`다. 소비 뒤 검증·저장 실패에도 marker를
  환불하지 않는다(at-most-once admission).
- commit 후 callback 전 AI process 종료 시 원 task는 PROCESSING TTL로 만료되고 final graph는 남는다 —
  자동 복구(durable receipt·redispatch)를 추가하지 않는 것이 수용된 MVP 한계다.
- `PROCESSING` TTL은 1시간, terminal task TTL은 24시간, callback token 소비 marker TTL은 25시간이며
  staging retention은 7일이다.
- `processingStartedAt`은 전처리·staging 저장 후 PROCESSING 저장 직전에 한 번 캡처하며 PROCESSING
  전용이다 — terminal 전이 시 보존하지 않고 폐기한다(terminal에 경과 시간을 제공하지 않음, TTL 불변).
- PROCESSING polling의 `elapsedSeconds`는 완료된 초이며 음수가 되지 않는다(시계 역행·future
  timestamp는 0 clamp). PROCESSING task에는 기준 시각이 항상 존재한다.

### Push

- 완료 푸시는 callback이 처음 확정한 terminal(markSuccess/markFailed 성공) 뒤에만 비동기 best-effort로
  예약한다 — terminal 저장 실패·token 거절 경로에는 알림이 없고,
  enqueue·발송 실패는 callback 200·Redis 상태·polling 계약을 바꾸지 않는다.
- 푸시는 조회를 유도하는 신호일 뿐 결과 전달 경로가 아니다 — payload는 일반 문구와
  `taskId`/`status`뿐이고 polling이 권위 원천이자 유실 안전망이다(durable retry/outbox 없음).
- FID는 전역 unique 단일 owner다. 등록·계정 전환은 원자 upsert(read-then-insert+예외 복구 금지),
  해제는 (owner, FID) 동시 일치할 때만 삭제한다(멱등 — 이전 owner의 늦은 해제가 재결합 등록을 못 지움).
- FCM 영구 무효(`UNREGISTERED`·target-level `INVALID_ARGUMENT`)만 등록을 삭제하고 인증·project
  mismatch·quota·internal 오류로는 삭제하지 않는다.
- FID 원문은 URL·application log·예외 메시지에 남기지 않으며 access log body에서 마스킹된다.

### Photos

- S3 key는 서버가 userId와 filename에서 파생하며 client가 full key를 정하지 않는다.
- presigned PUT은 content type과 content length를 서명에 묶는다.
- `photoUrl`은 save 시 materialize한다. CDN domain이나 key 규칙 변경에는 기존 payload backfill이 필요하다.
- Event PATCH의 수동 PHOTO는 client가 S3 업로드 성공 뒤 보내며 서버는 S3 object 존재 여부를 확인하지
  않는다. 입력 payload는 `description`·`photoUrl`을 받지 않고, final payload는 `description=null`과
  서버가 만든 `photoUrl`을 저장한다.
- 만료 PHOTO draft는 S3 삭제에 성공한 뒤 DB row를 삭제한다. S3 실패 때 row를 남겨 retry한다.
- finalized photo와 presign 후 draft가 생기지 않은 orphan object는 현재 cleanup 범위가 아니다.

### Authentication

- 사용자는 `(provider, provider_user_id)`로만 결합하며 email로 provider account를 merge하지 않는다.
- Kakao 재로그인은 non-null 닉네임만 갱신한다. 누락 claim은 동의 철회인지 provider 응답 누락인지
  구분할 수 없으므로 기존 값을 지우지 않는다.
- access JWT에는 `iss/sub/iat/exp`만 두고 PII를 넣지 않는다.
- refresh token raw value는 저장하지 않고 hash만 저장한다.
- refresh rotation과 reuse detection은 transactionally 처리하고 reuse 때 그 사용자의 refresh를 모두 revoke한다.
- App Code는 hash-key Redis entry로 저장하고 GETDEL로 한 번만 소비한다.
- `/a/api`는 유효한 자체 access JWT(Bearer)가 있어야 접근한다 — 무토큰/무효 토큰은 401 `-2001`
  단일 계약으로 수렴하고, 사유·token 원문은 응답·로그에 남기지 않는다.
- access JWT의 subject는 양수 userId만 유효하다(0·음수는 발급 거절·인증 실패 — 과거 user 0 데이터 접근 차단).
- 요청 하나의 principal userId가 draft record 조회·날짜 guard·enrich photo key·staging row·
  Redis task owner·polling·DailyRecord 전체/단건 조회·편집/삭제 소유권 검사까지 전부 동일해야 한다
  (지점 분기 금지).
- Redis draft task owner와 dailyRecordId는 세 상태 모두 필수로 보존된다. polling은 상태 분기 전에
  owner를 대조하고 타 사용자 task는 404 `-1001`로 은닉한다.
- callback은 request principal이 아니라 task 저장 owner를 쓴다. source owner와 record owner의 일치는
  AI validation이 검증한다.

## Known Gaps

- DRAFT→SAVED 사용자 전이, emotion 입력 API가 없다.
- 실 AI writer(Laimory-AI)의 direct-write 구현은 별도 저장소 진행분이다.
- photo orphan cleanup과 automatic deployment rollback이 없다.

## Update When

위 규칙을 강제하는 schema, service, security, Redis TTL 또는 cleanup 순서가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest
```
