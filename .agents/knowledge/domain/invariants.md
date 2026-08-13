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

- `recordDate`는 클라이언트 요청값을 서버 계산·보정 없이 그대로 쓰고 `(subject_id, record_date)`는 유일하다.
- draft 요청의 `timelineWindow`는 필수값과 `startTime < endTime`만 검증하고, Redis에는 local 원본을
  보존하며 AI transport에는 record timezone 기반 offset ISO로 변환해 전달한다. `recordDate`·`recordAt`·
  window 상호 간 날짜 정합성은 검증하지 않는다(독립 계약).
- draft source item의 `startAt`은 전 타입 필수이고 `endAt`은 nullable이다(누락 `startAt`은 저장·외부
  호출 전 400).
- `rawId`는 draft source와 Event PATCH `photosToAdd` 양쪽에서 canonical lowercase UUID(8-4-4-4-12,
  version 무관 — `RawIds`)만 허용한다. 위반은 저장·AI dispatch 전 400이고 오류 메시지에 rawId 원문을
  싣지 않으며, 허용값은 서버 정규화 없이 그대로 저장한다(identity 불변).
- 저장 경계는 v1 privacy 치환 후의 값만 쓴다 — draft staging payload(enrich본 `redactTree`,
  `clientPhotoUri`만 storage 원문), AI 결과 Event `title`/`subtitle`/`question`(255자 token-aware
  bounded), User Memory 문서(`redactTree`). 치환 실패는 원문 fallback 없이 그 단계 전체를 중단한다
  (fail-closed — draft는 부수효과 전무, AI 결과는 callback token 미선점, User Memory는 task/dispatch
  미생성 또는 기존 문서 유지).
- 사용자 입력 원문(Event PATCH/memo PUT의 title·subtitle·memo, `clientPhotoUri`)은 DB·앱 응답에서
  유지하고 AI 전달 DTO 조립에서만 치환한다. User Memory base 지문은 접수 body의 치환본이 아니라
  DB 원본 문서로 계산한다.
- 지오코딩 부분 실패 품질 판정은 materialize된 unique coordinate 최종 outcome 기준이다 — `U>0`에서
  `5F > U`(실패 20% 초과, 정수 교차곱·정확히 20%는 허용) 또는 시간순 coordinate observation
  (STAY·MOVEMENT START는 `startAt`, MOVEMENT END는 `endAt`-or-`startAt`; `observationAt→rawId→START<END`
  안정 정렬) 연속 실패 3개면 저장 전 502로 거절한다. 같은 좌표 반복은 비율에서 1회, 연속 판정에서
  반복 횟수대로 센다. 판정은 request 배열·완료 순서와 무관하다(같은 outcome map → 같은 결과).
- 거절된 geo batch의 오류 코드는 materialized 실패 aggregate로 결정적이다 — 영구 실패가 하나라도 있으면
  `-1015`, 아니면 `-1014`. circuit 때문에 호출하지 못한 좌표의 가상 응답은 판정하지 않는다.
- 허용된 geo batch의 lookup map에는 모든 unique 입력 좌표 key가 있다 — 성공은 실제 값, 실패는
  `address=null`·`places=[]` fallback(실패 marker 필드 없음).
- rawId dedupe·기존 저장 item 제외 뒤 지오코딩 대상 unique coordinate는 최대 30개다(공개 제품 상한 —
  초과는 외부 호출 전 400, 운영 tuning으로 낮추지 않음).
- draft POST는 DailyRecord 선생성(find-or-create + `recordAt/recordTimezone` 갱신 + SAVED 재확인)과
  source 저장을 한 트랜잭션으로 AI dispatch 전에 커밋한다. Redis 저장 실패 시 source rows만 보상
  삭제하고 DailyRecord는 유지한다(empty DRAFT는 같은 날짜 재시도가 재사용, 자동 cleanup 없음).
- `SAVED` record에는 새 draft source를 append하지 않는다.
- 기존 final `rawId`(record의 Event→junction→Item 경로)와 같은 draft source는 제외하고 같은 request 안
  중복도 한 번만 취급한다. 결과 저장 transaction도 write 직전 같은 조건을 재검사한다(이중 방어 — DB UNIQUE 없음,
  race/legacy 중복 행 허용). Event PATCH의 PHOTO 추가는 request rawId 중복을 첫 항목 우선으로 접고,
  같은 record의 기존 PHOTO Item을 재사용하며 대상 Event에 이미 연결됐으면 no-op 처리한다. 같은 rawId의
  non-PHOTO Item이 있으면 입력 전체를 거절한다.
- 같은 날짜 append는 기존 event/item의 그룹·title·subtitle·memo를 바꾸지 않는다(append-only).
- Event↔Item 연결은 junction(`timeline_event_items`)이 유일 경로다. 한 Item은 같은 DailyRecord의 여러
  Event에 공유될 수 있고, 채택된 source 하나는 정확히 한 final Item이 된다(여러 Event 공유 시에도 1행).
- same-DailyRecord Item 공유는 DB 제약이 아니라 writer 계약이다 — AI·fake는 새 Item을 현재 task의 새
  Event에만 연결하고, Event PATCH는 같은 record의 기존 PHOTO를 대상 Event에 재사용할 수 있다.
- draft 결과 저장(Event/Item/junction 저장 + accepted source 삭제)은 **서버**가 하나의 DB
  transaction으로 commit한다. Event PATCH의 Event/memo 수정 + 수동 PHOTO Item/junction 추가도 서버가
  별도의 하나의 DB transaction으로 commit한다. AI는 어떤 테이블도 직접 쓰지 않는다.
- Redis `SUCCESS` 전이는 결과 transaction 뒤 task가 `CALLBACK_PENDING`일 때만 한다.
- event `startAt`의 정확한 충돌은 +10분씩 미는 best-effort다(적용 주체는 서버 결과 저장 transaction).
  DB unique invariant는 아니다. event `endAt`은 조정된 start보다 앞서지 않도록 clamp한다.
- final event `eventType`은 논리적 non-null이며 미분류는 `UNKNOWN` 단일 표현이다(별도 nullable 상태 없음).
  미지원 literal은 결과 저장 400이다(새 literal 활성화 순서: Server enum 배포 → AI writer 활성화).
- `DRAFT→SAVED` 전이는 조건부 UPDATE(`WHERE status='DRAFT'`)의 영향 행 수가 유일한 판정 기준이자 이
  흐름의 유일한 직렬화 지점이다. 사전 검증을 통과한 요청 둘이 겹쳐도 하나만 1을 받고 나머지는 부수효과
  없이 롤백된다(0행은 재조회로 이미 SAVED 409 / 없음·비소유 404로 분류).
- **저장 전이와 User Memory 교체는 하나의 transaction이 아니다** — 저장 API가 전이를, AI 결과 API가
  교체를 각각 commit한다. User Memory는 다음 타임라인 품질을 높이는 보조 데이터이고 그 갱신 성패가
  사용자의 저장 완료를 좌우하지 않는다.
- User Memory 갱신 접수 body와 base 문서 지문은 **사용자 guard를 잡은 뒤** 그 시점의 상태로 만든다.
  대기 중에 앞선 날짜의 갱신이 문서를 바꾸므로, 미리 조립하면 낡은 문서를 base로 삼게 된다.
- User Memory 결과 적용 여부는 base 문서 지문(SHA-256) 일치가 판정한다. 불일치는 그 사이 다른 날짜가
  문서를 교체했다는 뜻이라 결과를 폐기한다(409) — 적용하면 그 날짜의 기여가 조용히 사라진다.
- 사용자 단위 guard(`SET NX`) 획득 실패가 "그 사용자의 갱신이 진행 중"이라는 유일한 판정이다 — 별도의
  진행 상태 저장을 두지 않는다. 점유는 실패가 아니라 정상 직렬화이므로 버리지 않고 **그 지점에서**
  대기 큐에 남겨 하루 1회 배치가 다시 시도한다. 저장 자체는 guard와 무관하게 항상 성공한다.
- 갱신 대기 큐는 경합이 없으면 쓰이지 않는다 — 큐에 있는 것은 전부 "guard를 못 잡아 밀린 작업"이다.
- 미반영 큐를 정리하는 곳은 **결과 endpoint 하나다** — 성패를 아는 유일한 지점이라 접수 시점에는 지우지도
  넣지도 않는다. 반영되면 빼고, 반영하지 못하면(FAILED·지문 불일치·계약 위반) 넣어 다음 배치가 재시도한다.
- 큐 항목은 최초 기록 시각을 유지한다(ZADD NX) — 재시도로 시한이 연장되면 영영 안 되는 날이 남는다.

### Deletion

- Event·DailyRecord 삭제와 Event-Item 연결 해제는 DRAFT record에서만 허용한다. SAVED는 모든 작업 전에
  거절하고 없음·비소유는 404로 은닉한다.
- 날짜 기반 DailyRecord 삭제는 `(request subjectId, recordDate)`로 소유 record의 ID를 snapshot하고 삭제
  transaction이 그 정확한 ID의 owner/DRAFT를 다시 확인한다. lookup 뒤 같은 날짜 record가 재생성돼도 새
  record를 대신 삭제하지 않는다. deprecated ID 경로도 같은 삭제 transaction을 사용한다.
- 삭제 transaction은 다른 Event가 참조하지 않아 association 0이 될 orphan Item을 계산하고, orphan PHOTO의
  full object key와 원문 Item PK를 `timeline_photo_delete_jobs`에 insert한다. 같은 commit에서
  root/junction/non-PHOTO orphan은 hard delete하지만 유효한 PHOTO Item은 job과 함께 보존한다. 다른
  Event에도 연결된 shared Item/PHOTO는 유지하고 job을 만들지 않는다.
- DELETE API는 MySQL commit 뒤 S3 완료를 기다리지 않고 성공한다. 모든 process의 기본 스케줄은 매일
  03:00 `Asia/Seoul`이며 cron/zone을 환경에서 override할 수 있다. 각 worker는 외부 I/O 전에 짧은
  transaction에서 eligible job 최대 250개를 `FOR UPDATE SKIP LOCKED`로 claim하고 `available_at`을
  다음 KST calendar day 00:00으로 옮긴다. commit 뒤 `DeleteObjects`를 호출해 `Deleted`로 확인된 job과
  원문 PHOTO Item만 completion transaction에서 지운다. Error·응답 누락·SDK 예외·crash 행은 다음 일일
  실행에서 재시도하고, 이미 다른 worker가 완료한 행은 오류가 아니라 idempotent 완료로 수렴한다.
  애플리케이션이 실행 시각에 내려가 있으면 catch-up하지 않고 다음 실행까지 보존한다.
  state/attempt/backoff/token/lease/error/completed 이력과 Redis queue는 두지 않는다.
- 삭제 대상 PHOTO payload가 깨졌거나 filename/object key를 만들 수 없으면 job만 건너뛰고 hard delete는
  진행한다(orphan 허용 — draft cleanup과 동일 규칙).
- 같은 날짜의 draft(AI 작업), Event PATCH의 수동 PHOTO 추가, Event/DailyRecord 삭제 사이에는 공통
  Redis admission guard가 없다. 각 작업의 입력·소유권·DRAFT preflight와 자기 DB transaction 경계는
  유지하지만 서로를 날짜 단위로 직렬화하지 않는다. 과거 `timeline:date-guard:*` key는 읽거나 지우지
  않으며 기존 TTL로 자연 만료한다.
- 마지막 Event를 삭제해도 DailyRecord는 유지한다. 하루 전체 제거는 DailyRecord 삭제만 담당한다.
- Event/Record 행 삭제 시 자기 junction은 DB FK `ON DELETE CASCADE`가 지운다(JPA cascade 없음).
  Item은 record FK가 없어 cascade되지 않는다. 삭제 대상에만 연결된 non-PHOTO와 job을 만들 수 없는 손상
  PHOTO는 같은 transaction에서 명시 삭제하고, 유효한 PHOTO는 job과 함께 보존한다. orphan 판정은 삭제 전
  junction 스냅샷 기준이다. 같은 날짜 graph 쓰기를 직렬화하는 공통 guard가 없으므로 경합 정합성은
  보장하지 않는다.
- Event-Item 연결 해제는 대상 junction 한 줄만 직접 DELETE로 지우고 Event·shared Item은 유지한다. 현재
  정책상 연결된 PHOTO만 허용한다(non-PHOTO는 400, 미연결·없음·비소유는 타입 무관 404 은닉 우선). 직접
  DELETE의 영향 행 수가 판정 기준이라 같은 junction의 동시 해제 후발 요청은 stale-state 500 없이 404로
  수렴한다. 잔여 association 판정은 자기 삭제를 반영한 일반 읽기 best-effort다 — 서로 다른 junction의
  동시 해제가 겹치면 마지막 참조를 shared로 오판해 job 없는 orphan Item이 남을 수 있다(root 삭제의
  스냅샷 orphan 판정 경합과 같은 계열이며, 원인 불문 orphan을 수렴시키는 스위퍼는 후속 과제다).
  마지막 참조 orphan 처리(유효 PHOTO job 보존·손상 PHOTO 즉시 삭제)는 root 삭제와 같은 규칙이다.

### AI 서버간 계약

- AI는 MySQL·Redis에 직접 접근하지 않는다 — 입력은 서버간 입력 조회 API로 받고 결과는 결과 저장 API로 보낸다.
- AI dispatch body는 `taskId`·`taskToken`·`dailyRecordId`·offset `window`다. 입력 조회와 결과 저장 성공
  응답은 후속 단계가 사용할 새 `taskToken`을 body로 반환한다. 서버는 현재 token 원문 대신 SHA-256
  hash만 Redis task에 저장하고 모든 요청에서 다시 검증한다.
- 호출 순서는 PROCESSING task의 내부 `ProcessStage`
  (`INPUT_PENDING → RESULT_PENDING → CALLBACK_PENDING`)가 제한한다.
- token hash+stage 교체와 callback terminal 전이는 현재 Redis task JSON 전체를 기대값으로 비교하는 Lua
  CAS다.
- 입력 조회는 토큰·PROCESSING 검증을 개인 데이터 조회보다 먼저 수행한다. 응답에 `userId`·`dailyRecordId`·
  행 PK를 담지 않으며 source는 `rawId`로만 식별한다.
- 결과 저장은 새 callback token hash와 `CALLBACK_PENDING`을 CAS로 선점한 요청 하나만 실행한다. MySQL
  실패가 호출부로 돌아오면 가능한 경우 이전 result token hash와 RESULT_PENDING으로 복구한다.
- 결과 저장 endpoint는 graph를 쓰고 내부 stage를 CALLBACK_PENDING까지 전이한다. 외부 task 상태를
  SUCCESS/FAILED로 종결하는 책임은 콜백만 가진다.
- callback body는 `status`, `errorCode`, `error`뿐이며 결과 graph를 전달하지 않는다.
- callback `errorCode`와 Redis FAILED task `error`는 음수 JSON integer다. 문자열 코드는 허용하지 않는다.
- callback·User Memory 결과의 자유 text `error`는 사용자 원문이 섞일 수 있어 저장·클라이언트 노출은
  물론 application log에도 남기지 않는다(수신 후 폐기 — taskId와 bounded numeric code만 로깅).
- SUCCESS 콜백은 CALLBACK_PENDING, FAILED는 INPUT_PENDING/RESULT_PENDING에서만 허용한다.
- terminal task에 같은 결과가 다시 오면 200(멱등), SUCCESS↔FAILED 상충은 409 `-1017`다.
- 결과 저장 commit 후 callback 전 AI process 종료 시 원 task는 PROCESSING TTL로 만료되고 저장된 graph는
  남는다 — 자동 복구(redispatch)를 추가하지 않는 것이 수용된 MVP 한계다.
- `PROCESSING` TTL은 3분이며 token/stage 교체마다 다시 확보한다(`processingStartedAt`은 보존).
  terminal task TTL은 24시간, staging retention은 7일이다.
- Redis와 MySQL은 분산 transaction으로 묶지 않는다. token 교체 뒤 프로세스 종료 또는 MySQL commit 뒤
  result 응답 유실 시 AI가 callback token을 얻지 못하고 task가 TTL 만료될 수 있으며 자동 reconciliation은 없다.
- PROCESSING 만료는 key 소멸이지 FAILED 전이가 아니다 — scheduler가 만료 task를 복구하지 않고 이후
  폴링·서버간 요청은 404(`-1001`)다. AI dispatch 실패 시 draft POST는 502(`-1009`)이며 taskId를 반환하지
  않는다(202는 접수 확인에만 해당). UNKNOWN 502 뒤에도 AI가 3분 안에 단계를 마치면 유효하다 — 502를
  미접수 증명으로 삼아 자동 재전송하지 않는다.
- `processingStartedAt`은 전처리·staging 저장 후 PROCESSING 저장 직전에 한 번 캡처하며 PROCESSING
  전용이다 — TTL 재확보에도 바뀌지 않고 terminal 전이 시 폐기한다.
- subject별 진행 작업 index(`timeline:draft-task:user:{canonicalUuid(subjectId)}:processing`)는 조회 후보일 뿐이다 —
  task JSON의 status/owner가 유일한 권위이며 index 단독으로 응답을 만들지 않는다. 목록 API는 principal
  소유 PROCESSING taskId만 최신순으로 반환하고 만료·terminal·타인 소유 member는 존재 비노출로 제외 후
  요청 사용자 index에서만 best-effort ZREM한다(역직렬화 불가 JSON은 500이며 자동 삭제하지 않는다).
- PROCESSING 저장은 task JSON+전역 index+사용자 index(+사용자 index key TTL 갱신)를, terminal 저장은
  task JSON+두 index ZREM을 각각 한 Lua 실행 경계로 쓴다 — `TimelineTaskStore#save`가 모든 lifecycle
  전이의 단일 write 지점이다. 사용자 index key TTL은 마지막 PROCESSING 저장 뒤 3분 inactivity cleanup
  이며 member별 TTL이 아니다.
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

- S3 key는 서버가 subjectId와 filename에서 파생하며 client가 full key를 정하지 않는다.
- presigned PUT은 content type과 content length를 서명에 묶는다.
- `photoUrl`은 save 시 materialize한다. CDN domain이나 key 규칙 변경에는 기존 payload backfill이 필요하다.
- Event PATCH의 수동 PHOTO는 client가 S3 업로드 성공 뒤 보내며 서버는 S3 object 존재 여부를 확인하지
  않는다. 입력 payload는 `description`·`photoUrl`을 받지 않고, final payload는 `description=null`과
  서버가 만든 `photoUrl`을 저장한다.
- 삭제된 PHOTO를 다시 추가하는 것은 새 upload identity다. Android는 같은 로컬 사진이어도 presign을
  새로 요청하고 응답의 새 filename만 Event PATCH에 넣으며, 삭제 job이 가진 과거 filename을 재사용하지
  않는다. 이미 S3 업로드를 마친 **동일 pending addition**의 PATCH 재시도만 그 pending filename을
  보존할 수 있다. 서버는 pending delete key 재사용을 별도로 차단하지 않는다.
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
- 인증 filter가 만든 raw `Long` principal은 timeline/push controller 경계의 `@CurrentSubject` resolver가
  `SubjectMappingService.getRequired`로 한 번 변환한다. 변환된 request UUID subjectId가 draft record 조회·
  enrich photo key·staging row·Redis task owner·polling·DailyRecord/Event 조회·편집·삭제·push 등록 소유권
  검사까지 전부 동일해야 한다(지점 분기 금지, mapping 누락은 자동 생성 없이 fail-closed).
- Redis draft task owner와 dailyRecordId는 세 상태 모두 필수로 보존된다. polling은 상태 분기 전에
  owner를 대조하고 타 사용자 task는 404 `-1001`로 은닉한다.
- 서버간 요청(입력·결과·콜백)은 request principal이 아니라 task 저장 owner를 쓴다. 결과가 참조하는
  source가 이 task 소유인지는 결과 저장 transaction이 검증한다.

## Known Gaps

- emotion 입력 API가 없다(저장 API 범위 밖 — `emotion_type`은 계속 null이다).
- User Memory 갱신이 AI FAILED·deadline(7일) 초과로 끝내 안 된 날은 그 날의 내용이 memory에 반영되지
  않는다. guard 충돌 누락은 대기 큐 + 하루 1회 재시도가 없앴고, 남은 구멍은 MQ 도입과 함께 다룬다
  (로그로만 관측).
- 실 AI(Laimory-AI)의 서버간 입력·결과 호출 구현은 별도 저장소 진행분이다.
- photo orphan cleanup과 automatic deployment rollback이 없다.
- 같은 날짜 draft·수동 PHOTO 추가·삭제가 겹칠 때의 graph 정합성 보장은 미구현이다. 공통 Redis
  admission guard와 대체 DB lock·retry·upsert가 모두 없다.

## Update When

위 규칙을 강제하는 schema, service, security, Redis TTL 또는 cleanup 순서가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest
```
