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
  값 범위는 MySQL `DATE`와 같은 `1000-01-01`~`9999-12-31`(양끝 포함)이며, `{recordDate}` path 다섯 API는
  범위 밖 값을 service 호출 전에 400 `-400`으로 거절한다. 미래 날짜는 **하루 기록을 만드는 draft 생성
  하나만** 요청 `recordTimeZone` 기준으로 거절한다 — 오늘 판정은 서버 zone이 아니라 그 기록의 timezone이
  결정한다. `DailyRecord` 생성 경로가 draft 하나뿐이라 이 경계 하나로 미래 날짜 record 자체가 생기지
  않으므로, save를 포함한 나머지 API에는 미래 검사를 두지 않는다.
- draft 요청의 `timelineWindow`는 필수값과 `startTime < endTime`만 검증하고, Redis에는 local 원본을
  보존하며 AI transport에는 record timezone 기반 offset ISO로 변환해 전달한다. `recordDate`·`recordAt`·
  window 상호 간 날짜 정합성은 검증하지 않는다(독립 계약).
- draft source item의 `startAt`은 전 타입 필수이고 `endAt`은 nullable이다(누락 `startAt`은 저장·외부
  호출 전 400).
- draft `HEALTH` source item의 metric은 걸음 수 `STEPS`만 허용한다. `DISTANCE`·`SLEEP` 등 다른
  literal은 HTTP 역직렬화에서 400으로 거절되어 DB/Redis 저장과 AI dispatch에 도달하지 않는다.
- `rawId`는 draft source와 수동 PHOTO `photosToAdd`(Event PATCH·Event 생성 POST)에서 canonical
  lowercase UUID(8-4-4-4-12, version 무관 — `RawIds`)만 허용한다. 위반은 저장·AI dispatch 전 400이고
  오류 메시지에 rawId 원문을 싣지 않으며, 허용값은 서버 정규화 없이 그대로 저장한다(identity 불변).
- 저장 경계는 v1 privacy 치환 후의 값만 쓴다 — draft staging payload(enrich본 `redactTree`,
  storage 원문 보존 필드는 `clientPhotoUri`·`filename`·`photoUrl`), AI 결과 Event
  `title`/`subtitle`/`question`/`place`/`address`(255자
  token-aware bounded), User Memory 문서(`redactTree`). 치환 실패는 원문 fallback 없이 그 단계 전체를
  중단한다(fail-closed — draft는 부수효과 전무, AI 결과는 callback token 미선점, User Memory는
  task/dispatch 미생성 또는 기존 문서 유지).
- storage redaction 예외는 두 부류다. (a) 사용자 입력 원문(Event PATCH/memo PUT의 title·subtitle·memo,
  `clientPhotoUri`)은 DB·앱 응답에서 유지하고 AI 전달 DTO 조립에서만 치환한다. (b) 서버 파생 식별자
  (`filename`·`photoUrl`)는 치환하지 않고 AI에도 원문 그대로 전달한다 — AI가 `photoUrl`을 HTTP GET으로
  소비하기 때문이다. (b)에 PII가 들어올 수 없는 근거는 입력 경계의 `PhotoFilenames.requireValid`
  전체 일치 검증과 서버 조립 URL이다. 두 값은 hex 문자열이라 치환 대상에 두면 CARD·PHONE 탐지기에
  우연히 걸려 이미지 URL·S3 object key가 영구 손상된다(#387). User Memory base 지문은 접수 body의 치환본이 아니라
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
  race/legacy 중복 행 허용). 수동 PHOTO 추가(Event PATCH·Event 생성 POST)는 request rawId 중복을 첫
  항목 우선으로 접고, 같은 record의 기존 PHOTO Item을 재사용하며 대상 Event에 이미 연결됐으면 no-op
  처리한다. 재사용 PHOTO의 저장된 startAt/endAt과 클라이언트 입력 payload가 요청과 다르거나 같은 rawId의
  non-PHOTO Item이 있으면 입력 전체를 거절한다.
- 같은 날짜 append는 기존 event/item의 그룹·title·subtitle·memo를 바꾸지 않는다(append-only).
- Event↔Item 연결은 junction(`timeline_event_items`)이 유일 경로다. 한 Item은 같은 DailyRecord의 여러
  Event에 공유될 수 있고, 채택된 source 하나는 정확히 한 final Item이 된다(여러 Event 공유 시에도 1행).
- same-DailyRecord Item 공유는 DB 제약이 아니라 writer 계약이다 — AI·fake는 새 Item을 현재 task의 새
  Event에만 연결하고, 수동 PHOTO 추가는 같은 record의 기존 PHOTO를 대상 Event에 재사용할 수 있다.
- draft 결과 저장(Event/Item/junction 저장 + accepted source 삭제)은 **서버**가 하나의 DB
  transaction으로 commit한다. Event PATCH의 Event/memo 수정 + 수동 PHOTO Item/junction 추가와 수동
  Event 생성의 Event + optional PHOTO Item/junction 추가도 각각 서버의 하나의 DB transaction으로
  commit한다. AI는 어떤 테이블도 직접 쓰지 않는다.
- Redis `SUCCESS` 전이는 결과 transaction 뒤 task가 `CALLBACK_PENDING`일 때만 한다.
- event `startAt`의 정확한 충돌은 +10분씩 미는 best-effort다(적용 주체는 서버 결과 저장 transaction).
  DB unique invariant는 아니다. event `endAt`은 조정된 start보다 앞서지 않도록 clamp한다.
- final event `eventType`은 논리적 non-null이며 미분류는 `UNKNOWN` 단일 표현이다(별도 nullable 상태 없음).
  미지원 literal은 결과 저장 400이다(새 literal 활성화 순서: Server enum 배포 → AI writer 활성화).
- `DRAFT→SAVED` 전이는 조건부 UPDATE(`WHERE status='DRAFT'`)의 영향 행 수가 유일한 판정 기준이자 이
  흐름의 유일한 직렬화 지점이다. 요청 필수 `emotionType`(5단계 enum)과 `status=SAVED`는 이 UPDATE
  하나로 함께 **최초 확정**된다 — 부분 상태는 없다. 사전 검증을 통과한
  요청 둘이 겹쳐도 하나만 1을 받아 승자의 감정만 남고 나머지는 부수효과
  없이 롤백된다(0행은 재조회로 이미 SAVED 409 / 없음·비소유 404로 분류). 저장 전 DRAFT와 과거
  SAVED 행의 null 감정은 backfill하지 않는 정상 legacy 값이다.
- 감정의 write 지점은 둘뿐이다 — save의 최초 확정 UPDATE와, SAVED 전용 감정 수정 PUT의 조건부
  UPDATE(`WHERE status='SAVED'`, status 불변). 후자도 영향 행 수가 판정 기준이고 0행은 재조회로
  DRAFT 409 `-1020` / 없음·비소유 404 / 동일 감정 SAVED 멱등 성공으로 분류한다. DRAFT에 감정을
  미리 쓰는 경로는 없으며(`DRAFT + non-null emotionType` 상태 없음), 감정 수정은 User Memory 갱신을
  새로 enqueue하지 않는다.
- 수동 Event 생성(`POST .../daily-records/{recordDate}/events`)은 기존 DailyRecord에만 허용한다
  (DRAFT/SAVED 모두, 없음·비소유 404 은닉 — DailyRecord 자동 생성 없음). 소유 record 재확인·Event
  insert·optional PHOTO Item/junction 추가는 하나의 transaction이다. 사진 분류·저장 실패 시 Event
  insert까지 rollback한다. 수동 Event의 `question`/`place`/`address`는 항상 null이고, 시각은 보낸 값
  그대로 저장한다(+10분 충돌 보정은 AI 결과 저장 전용). 상세 필드 규칙(title·subtitle·시간·memo)과
  사진 입력 규칙은 각각 Event PATCH와 같은 단일 규칙을 공유한다.
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

- Event·DailyRecord 삭제와 Event-Item 연결 해제는 record 상태와 무관하게 허용한다(SAVED 포함).
  없음·비소유는 404로 은닉한다.
- 날짜 기반 DailyRecord 삭제는 `(request subjectId, recordDate)`로 소유 record의 ID를 snapshot하고 삭제
  transaction이 그 정확한 ID의 owner/DRAFT를 다시 확인한다. lookup 뒤 같은 날짜 record가 재생성돼도 새
  record를 대신 삭제하지 않는다. deprecated ID 경로도 같은 삭제 transaction을 사용한다.
- 삭제 transaction은 다른 Event가 참조하지 않아 association 0이 될 orphan Item을 계산하고, orphan PHOTO의
  full object key와 원문 Item PK를 `timeline_photo_delete_jobs`에 insert한다. 같은 commit에서
  root/junction/non-PHOTO orphan은 hard delete하지만 유효한 PHOTO Item은 job과 함께 보존한다. 다른
  Event에도 연결된 shared Item/PHOTO는 유지하고 job을 만들지 않는다.
- DELETE API는 MySQL commit 뒤 S3 완료를 기다리지 않고 성공한다. 모든 process의 기본 스케줄은 매일
  03:00 `Asia/Seoul`이며 cron/zone을 환경에서 override할 수 있다. job의 처리 기회는 KST 생성일 D 기준
  D+1~D+3 일일 실행뿐이다. 각 worker는 외부 I/O 전에 짧은 transaction에서 처리 창 안이면서 오늘 아직
  처리하지 않은(`updated_at < 오늘 00:00`) job 최대 250개를 `FOR UPDATE SKIP LOCKED`로 claim하고
  `PENDING`/stale `PROCESSING`을 `PROCESSING`으로 전이하면서 `updated_at`을 claim 시각으로 갱신한다.
  commit 뒤 `DeleteObjects`를 호출해 `Deleted`로 확인된 job과
  원문 PHOTO Item만 completion transaction에서 지운다. Error·응답 누락·SDK 예외·crash 행은 처리 창
  안의 다음 일일 실행에서 재시도하고, 정상 실패는 `PENDING`으로 되돌린다. 이미 다른 worker가 완료한
  행은 오류가 아니라 idempotent 완료로 수렴한다. 처리 창을 벗어난 미완료 job은 재시도 없이 원문 Item과
  함께 보존하고 worker가 건수만 ERROR 로그로 경보한다.
  애플리케이션이 실행 시각에 내려가 있으면 catch-up하지 않고 다음 실행까지 보존한다 — 실제 시도 횟수는
  보장하지 않는다.
  별도 attempt/token/error/completed 이력과 Redis queue는 두지 않는다.
- 삭제 대상 PHOTO payload가 깨졌거나 filename/object key를 만들 수 없으면 job만 건너뛰고 hard delete는
  진행한다(orphan 허용 — draft cleanup과 동일 규칙).
- 같은 날짜의 draft(AI 작업), 수동 Event 생성/편집(수동 PHOTO 추가 포함), Event/DailyRecord 삭제
  사이에는 공통 Redis admission guard가 없다. 각 작업의 입력·소유권 preflight와 자기 DB transaction
  경계는 유지하지만 서로를 날짜 단위로 직렬화하지 않는다. 과거 `timeline:date-guard:*` key는 읽거나
  지우지 않으며 기존 TTL로 자연 만료한다.
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
  스냅샷 orphan 판정 경합과 같은 계열). 원인 불문 이런 orphan은 일일 스위퍼가 수렴시킨다.
  마지막 참조 orphan 처리(유효 PHOTO job 보존·손상 PHOTO 즉시 삭제)는 root 삭제와 같은 규칙이다.
- **junction이 0인 final Item은 항상 쓰레기다.** Item과 junction은 언제나 한 transaction에서 insert되므로
  (AI 결과 store·수동 PHOTO link) 커밋된 0-junction Item을 되살리는 요청 경로가 없다. 일일 스위퍼가
  이를 전제로 수렴시킨다 — 유효 PHOTO는 delete job으로 넘기고 non-PHOTO와 key를 복원할 수 없는 손상
  PHOTO만 즉시 hard delete하며, job이 이미 있는 Item은 worker 소유라 건드리지 않는다.
- **같은 object key를 가리키는 살아 있는 Item의 S3 객체는 절대 지우지 않는다.** 방어는 두 지점이다 —
  스위퍼는 enqueue 전에, worker는 S3 호출 직전에 같은 key를 참조하는 junction 있는 Item을 확인하고,
  있으면 job을 만들지 않거나(스위퍼) 이미 만든 job을 취소한다(worker). 판정은 filename을 coarse filter로
  쓰되 full object key 일치로 확정한다. 살아 있는 쪽의 key는 저장된 `photoUrl`이 아니라 소유 subject에서
  계산해(`SHA2(UNHEX(REPLACE(subject_id,'-','')),256)` = `PhotoObjectKeys.subjectNamespace`) 저장본이
  손상돼 있어도 보호가 유지된다. 같은 key의 orphan만 여럿이면 최소 `timeline_item_id`가 job 소유자이고
  나머지 행은 삭제된다(삭제 순서에 의존하지 않는 규칙).
- 스위퍼는 후보를 PK 지정 `FOR UPDATE SKIP LOCKED`로 claim해 process 간에 나눈다. 탐색 statement에는
  잠금을 걸지 않는다(전량 anti-join이라 `REPEATABLE READ`에서 테이블이 잠긴다). run 종료 조건은 탐색이
  비는 것뿐이고, claim·재검증이 비어도 커서만 올려 계속한다. 잠금 하 job 재검증은 반드시 current read다
  — 무잠금 탐색이 고정한 snapshot으로는 동시 생성된 job을 못 봐 FK 위반으로 batch가 깨진다.
  삭제 요청이 스위퍼가 잠근 행의 FK 부모 잠금을 기다리거나 드물게 deadlock으로 한쪽이 롤백되는 것은
  되돌릴 수 있는 실패로 수용한다.
- `filename` 자체가 손상된 살아 있는 Item은 coarse filter에 잡히지 않아 두 방어를 모두 통과한다.
  #387 배포 이전 저장분에만 존재하는 상태이며 복구하지 않고 수용한다.

### AI 서버간 계약

- AI는 MySQL·Redis에 직접 접근하지 않는다 — 입력은 서버간 입력 조회 API로 받고 결과는 결과 저장 API로 보낸다.
- AI dispatch body는 `taskId`·`taskToken`·`dailyRecordId`·offset `window`다. 입력 조회와 결과 저장 성공
  응답은 후속 단계가 사용할 새 `taskToken`을 body로 반환한다. 서버는 현재 token 원문 대신 SHA-256
  hash만 Redis task에 저장하고 모든 요청에서 다시 검증한다.
- 호출 순서는 PROCESSING task의 내부 `ProcessStage`
  (`INPUT_PENDING → RESULT_PENDING → CALLBACK_PENDING`)가 제한한다.
- token hash+stage 교체는 native `SET XX KEEPTTL`, callback terminal 전이는 native `SET XX PX`다 —
  timeline task에 Lua script는 없다. `XX`는 key 존재만 보고 기존 값을 비교하지 않으므로(expected-value
  CAS 아님) 잘못된 요청 차단은 write 전 token/status/stage 검증이 담당하고, write `false`(만료)는 task를
  부활시키지 않고 404 `-1001`로 수렴한다. 이 계약이 보장하는 것은 통제된 단일·순차 AI writer의 timeout
  재시도 멱등성이며, 같은 task/token 요청 둘이 첫 state write 전에 실제 동시 실행되는 경우는
  last-write-wins로 수용한다(현재 그런 writer 경로 없음).
- 입력 조회는 토큰·PROCESSING 검증을 개인 데이터 조회보다 먼저 수행한다. 응답에 `userId`·`dailyRecordId`·
  행 PK를 담지 않으며 source는 `rawId`로만 식별한다.
- 결과 저장은 retry receipt에 선점 표식(`claimedAt`)을 심는 write로 선점하고, 저장된 claim을 읽은 뒤늦은
  same-token 재시도는 409로 끝나 transaction에 재진입하지 않는다. 선점은 token을 바꾸지 않으며,
  callback token 회전과 `CALLBACK_PENDING` 전이는 MySQL commit 뒤 한 번의 native write로 함께 일어난다.
  MySQL 실패가 호출부로 돌아오면 최초 RESULT_PENDING snapshot으로 선점을 되돌린다(token은 그대로다).
  회전이 commit 뒤라 stage만으로는 transaction 진행 구간이 구분되지 않으므로, 이 표식이 없으면 뒤늦은
  요청이 겹쳐 돌아 Event·Item이 중복 삽입된다.
- 선점(`RESULT_PENDING` + `claimedAt`) 중 FAILED callback은 409로 거절한다 — 아직 살아 있는 transaction의
  commit 회전·선점 해제(`SET XX`)가 먼저 확정된 terminal 위에 PROCESSING을 되쓰는 것을 막는다. 선점 뒤
  crash한 task는 기존 계약대로 TTL 만료(404)로 끝난다.
- `CALLBACK_PENDING` 도달이 graph 확정의 유일한 증거다 — MySQL commit 뒤 callback token과 stage를 하나의 Redis write로 회전하므로, 이 stage는 commit 이후에만 존재한다. 응답 유실 뒤 재시도 창 안에 소비된 result token으로
  다시 오면 MySQL을 건드리지 않고 새 callback token만 재발급한다(응답 shape는 신규 저장과 동일).
  receipt 부재·창
  만료·terminal은 401 `-1002`, 선점 중(commit 전) 중복 요청은 409 `-1017`다. 재시도 body는 적용 경로가 없어 대조
  없이 무시한다.
- 입력 조회도 같은 방식으로 재조회를 받아준다 — 소비된 input token으로 창 안에 다시 오면 입력을
  재조립하고 새 result token을 재발급한다. 읽기라 선점 표식도 commit 증거도 없다.
- retry receipt는 PROCESSING 전용이며 terminal 전이가 stage와 함께 버린다. 재발급은 receipt를 갱신하지
  않는다 — 창의 기산점은 첫 요청 도착 시각이며 재시도로 미끄러지지 않는다.
- 결과 저장 endpoint는 graph를 쓰고 내부 stage를 CALLBACK_PENDING까지 전이한다. 외부 task 상태를
  SUCCESS/FAILED로 종결하는 책임은 콜백만 가진다.
- callback body는 `status`, `errorCode`, `error`뿐이며 결과 graph를 전달하지 않는다.
- callback `errorCode`와 Redis FAILED task `error`는 음수 JSON integer다. 문자열 코드는 허용하지 않는다.
- callback·User Memory 결과의 자유 text `error`는 사용자 원문이 섞일 수 있어 저장·클라이언트 노출은
  물론 application log에도 남기지 않는다(수신 후 폐기 — taskId와 bounded numeric code만 로깅).
- SUCCESS 콜백은 CALLBACK_PENDING, FAILED는 INPUT_PENDING/미선점 RESULT_PENDING에서만 허용한다(선점 중 FAILED 409는 위 claim guard 항목).
- terminal task에 같은 결과가 다시 오면 200(멱등), SUCCESS↔FAILED 상충은 409 `-1017`다.
- 결과 저장 commit 후 callback 자체가 오지 않으면 원 task는 PROCESSING TTL로 만료되고 저장된 graph는
  남는다 — 자동 복구(redispatch)를 추가하지 않는 것이 수용된 MVP 한계다.
- `PROCESSING` TTL은 최초 PROCESSING 저장 기준 절대 3분이다 — token/stage 교체는 `KEEPTTL`이라 만료
  시각을 연장하지 않으며, 폴링 `elapsedSeconds`·stuck 관측·task 만료가 전부 최초 `processingStartedAt`
  기준으로 일치한다. terminal task TTL은 24시간, staging retention은 7일이다.
- Redis와 MySQL은 분산 transaction으로 묶지 않는다. commit 뒤 응답 유실은 재시도 창 안의 재요청이
  복구하지만, 선점 뒤 **commit 전** 프로세스 종료는 stage가 `RESULT_PENDING`에 머물러 복구되지 않고 창 만료 뒤
  재요청도 401이다 — 그 task는 TTL 만료로 끝나며 자동 reconciliation은 없다.
- PROCESSING 만료는 key 소멸이지 FAILED 전이가 아니다 — scheduler가 만료 task를 복구하지 않고 이후
  폴링·서버간 요청은 404(`-1001`)다. AI dispatch 실패 시 draft POST는 502(`-1009`)이며 taskId를 반환하지
  않는다(202는 접수 확인에만 해당). UNKNOWN 502 뒤에도 AI가 3분 안에 단계를 마치면 유효하다 — 502를
  미접수 증명으로 삼아 자동 재전송하지 않는다.
- `processingStartedAt`은 전처리·staging 저장 후 PROCESSING 저장 직전에 한 번 캡처하며 PROCESSING
  전용이다 — stage write에도 바뀌지 않고 terminal 전이 시 폐기한다.
- subject별 진행 작업 index(`timeline:draft-task:user:{canonicalUuid(subjectId)}:processing`)는 조회 후보일 뿐이다 —
  task JSON의 status/owner가 유일한 권위이며 index 단독으로 응답을 만들지 않는다. 목록 API는 principal
  소유 PROCESSING taskId만 최신순으로 반환하고 만료·terminal·타인 소유 member는 존재 비노출로 제외 후
  요청 사용자 index에서만 best-effort ZREM한다(역직렬화 불가 JSON은 500이며 자동 삭제하지 않는다).
- task JSON은 먼저 저장하고 보조 전역·사용자 processing index는 native Redis 명령으로 갱신한다.
  최초 PROCESSING 생성만 ZADD(+사용자 index PEXPIRE 3분)하고, terminal 전이만 ZREM한다 — 중간
  stage write는 index에 어떤 명령도 보내지 않는다. 각 명령 실패·PEXPIRE=false는 task를 다시 읽지 않고
  같은 의도의 명령을 한 번 재시도한다(두 번째 실패는 metric·warn 관측만). task JSON status/owner가
  유일한 권위다. 사용자 index key TTL은 마지막 **task 생성** 뒤 3분 inactivity cleanup이며 member별
  TTL이 아니다 — task 수명도 생성 기준 3분이라 index가 유효 member보다 먼저 사라지지 않는다.
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
- 예정 알림의 발송 판정 축은 `예정 알림 마스터 ON + 일일 알림 ON + 활성 FID`다(#314). 마스터 행 부재는
  추정하지 않고 발송 대상에서 제외한다.
- 타임라인 완료 통지는 **마스터 스위치와 무관하게 발송한다** — 사용자가 직접 시작한 작업의 결과
  통지라 예정(리텐션) 알림과 성격이 다르다. 따라서 마스터가 실제로 막는 것은 예정 알림뿐이다.
  탈퇴 회원의 FID는 #367부터 보존되므로, 탈퇴 직전 시작해 task TTL(3분) 안에 완료된 in-flight 작업
  하나가 완료 push를 받을 수 있다 — 내용이 taskId·상태뿐인 일반 문구라 이 좁은 창을 수용한다
  (설정을 끈 정상 사용자의 완료 통지를 함께 막는 대가가 더 크다).
- 일일 리마인더는 기본 ON이고 발송 시각은 서버가 21:00(`Asia/Seoul`)으로 고정한다(#318). 사용자
  조작은 일일 알림 ON/OFF뿐이며 시각을 바꾸는 입력 경로는 없다 — 조회 응답의 시각은 읽기 전용 표시값이다.
- 설정 조회는 쓰기를 하지 않으며 행이 없으면 쓰기와 같은 이유로 던진다 — 기본값으로 가리면 조회가
  "켜짐"이라 답하는데 worker는 없는 행을 claim하지 못해 실제 발송이 0이 된다.
- 설정 행은 가입 transaction과 rollout backfill만 만든다. 쓰기는 행을 만들지 않으며, 0행·행 부재는 그
  보장이 깨진 운영 신호라 조용히 넘기지 않고 던진다(복구는 backfill 재실행).
- 설정 쓰기(일일 알림 ON/OFF)는 `next_due_at`을 서버 고정 시각의 다음 미래 occurrence로 재장전한다.
  꺼져 있는 동안 worker가 claim하지 않아 과거로 굳은 값을 그대로 켜면, 허용 지연 안쪽이라 켠 직후
  tick이 예정에 없던 알림을 발송한다. 같은 이유로 기존 행을 일괄로 켜는 마이그레이션도 `next_due_at`을
  같은 문장에서 재장전해야 한다(#318).
- 일일 알림 설정은 **subject당 한 행**이다(#321 — 판별자 없음). 두 번째 일일 알림이 생기면 이 테이블에
  행이나 컬럼을 더하지 않고 새 테이블을 만든다. 발송 시각의 권위는 DB가 아니라 애플리케이션 상수라
  운영 SQL로도 바뀌지 않는다.
- 앱 온보딩 완료 여부의 단일 권위는 `subject_preferences.onboarding_completed`다(#382, 기본 false).
  약관 동의 이력·DailyRecord 존재 여부에서 계산하거나 동기화하지 않으며, 약관 개정도 저장된
  완료 상태를 되돌리지 않는다(재동의 강제는 terms gate의 별도 책임) — 두 상태를 엮으면 약관 개정이
  온보딩을 되살리고 온보딩이 동의를 대신하는 양방향 오염이 생긴다.
- 온보딩 완료는 **단방향 멱등 전이**다. `false → true` command만 있고 되돌리는 writer는 두지 않으며,
  이미 완료한 subject의 재호출도 matched row 기준으로 성공한다(값이 같아서 0행인 것이 아니라 0행은 행
  부재를 뜻한다 — 이 판정이 changed 기준으로 바뀌면 정상 재시도가 500이 된다).
- 온보딩 값과 알림 마스터는 서로를 덮지 않는다 — 두 쓰기 모두 컬럼 단위 조건 UPDATE다. 특히 논리
  탈퇴의 마스터 OFF는 온보딩 값을 초기화하지 않는다(#367로 행이 보존되므로 초기화하면 접근이 막힌
  옛 subject의 온보딩만 되살아나고, 재가입은 어차피 새 subject의 기본값 false를 쓴다).
- 현재 두 알림 종류 모두 정보성 통지다(일일 리마인더는 기본 ON 일괄 발송이며 수신거부 수단은 일일 알림
  OFF다 — 분류는 제품 결정으로 확정). 영리 목적의 광고성 알림을 추가하려면
  정보통신망법 제50조가 요구하는 수신 동의·야간 전송 제한·표기·무료 수신거부 수단을 함께 도입해야 한다.
- worker는 한 occurrence를 한 번만 claim한다(발송·지연 skip 어느 쪽이든 `next_due_at`을 현재 이후 첫
  occurrence로 전진). 하루 1회 캡은 없다 — 껐다 켜서 오늘 시각이 다시 미래가 되면 같은 날 다시 발송될
  수 있고(사용자 행동이므로 허용), 위 수용 edge에서는 같은 occurrence가 최대 한 번 더 갈 수 있다.
  claim transaction이 전진을 먼저 commit하고 FCM은 그 밖에서 호출하므로 전달 보장은 at-most-once
  best-effort다 — claim 뒤 실패한 occurrence는 자동 재발송하지 않는다.
- 허용 지연(기본 30분)을 넘긴 occurrence는 발송하지 않고 다음 occurrence로 넘긴다 — 장시간 중단 뒤
  복구가 새벽에 밀린 알림을 쏟아내지 않게 하는 상한이다.

### Photos

- S3 key는 서버가 subjectId와 filename에서 파생하며 client가 full key를 정하지 않는다.
- presigned PUT은 content type과 content length를 서명에 묶는다.
- `photoUrl`은 save 시 materialize한다. CDN domain이나 key 규칙 변경에는 기존 payload backfill이 필요하다.
- 수동 PHOTO는 client가 S3 업로드 성공 뒤 Event PATCH 또는 Event 생성 POST로 보내며 서버는 S3 object
  존재 여부를 확인하지 않는다. 입력 payload는 `description`·`photoUrl`을 받지 않고, final payload는
  `description=null`과 서버가 만든 `photoUrl`을 저장한다.
- 수동 PHOTO의 nullable startAt/endAt은 `timeline_items`의 MySQL `DATETIME` 정밀도에 맞춰 초 단위만
  허용하고 소수 초는 저장 전에 400으로 거절한다.
- 삭제된 PHOTO를 다시 추가하는 것은 새 upload identity다. Android는 같은 로컬 사진이어도 presign을
  새로 요청하고 응답의 새 filename만 Event PATCH에 넣으며, 삭제 job이 가진 과거 filename을 재사용하지
  않는다. 이미 S3 업로드를 마친 **동일 pending addition**의 PATCH 재시도만 그 pending filename을
  보존할 수 있다. 이때 같은 full object key의 `PENDING` delete job은 짧은 locking transaction에서
  취소하고 job이 보존하던 Item을 재연결한다. 유효한 `PROCESSING`이면 S3 삭제와 경합하지 않게 409
  `-1019`로 거절하며 같은 object key의 새 Item을 만들지 않는다.
- 만료 PHOTO draft는 S3 삭제에 성공한 뒤 DB row를 삭제한다. S3 실패 때 row를 남겨 retry한다.
- finalized photo와 presign 후 draft가 생기지 않은 orphan object는 현재 cleanup 범위가 아니다.

### Terms

- 약관 문서 행은 불변이다 — 개정·rollback은 기존 행 UPDATE가 아니라 새 immutable 버전 INSERT다. 게시된
  버전·효력일을 바꾸는 API는 없다.
- 약관 원문의 source of truth는 `docs/terms/drafts`의 Markdown이고, builder가 버전별 불변 HTML을
  `src/main/resources/terms-content`에 생성한다. `TermContentController`는 `/terms/{slug}/{version}`에서
  그 정적 byte와 1년 `immutable` cache header만 전달한다. 약관 DB·API 응답에는 Markdown/HTML을 담지
  않고 `content_url`만 두며, 요청·기동 중 page를 다시 HTTP 조회하거나 원문을 동적 렌더링하지 않는다.
- `content_url`은 게시 시점에 확정된 사실이라 저장하고 코드에서 역산하지 않는다 — 역산하면 게시 host·경로
  규칙을 바꾸는 순간 과거 버전 행이 조용히 다른 주소를 가리켜 동의 이력이 소급 변조된다. 서버가 강제하는
  것은 형식(https 절대 URI, NOT NULL)뿐이고 게시 위치는 운영 규약이다.
- enforcement 대상 5종의 current 행 **존재가 단일 약관 gate의 활성화 조건**이다. 개인정보 처리방침처럼
  조회만 하는 종류는 gate를 활성화하지 않는다. 서버는 `content_url`이 실제로 열리는지
  검증할 수 없으므로(요청·기동 중 HTTP 조회 금지, 기동 형식 검사는 멀쩡한 오타를 통과시킨다) 게시 page가
  200임을 확인한 뒤에만 행을 INSERT한다. 순서를 뒤집으면 gate가 미동의 사용자를 막는 동안 약관 page는
  열리지 않는 창이 생긴다 — 이 창을 닫는 것은 코드가 아니라 순서다.
- 게시된 버전 URL은 영구 불변이다 — 내용 수정·재사용·삭제를 하지 않고 개정은 새 version·새 URL로
  게시한다. 과거 버전 URL이 동의 이력의 유일한 원문 재현 근거이므로 도메인·path를 옮기더라도 기존 URL
  접근성을 보존한다. 이 보존은 서버가 검증하지 못하므로 게시 절차가 소유한다.
- 현재 문서는 `effective_at <= now(KST)`인 종류별 최신 행으로만 계산한다(별도 active flag 없음).
  `(term_type, version)`·`(term_type, effective_at)` UNIQUE가 버전 식별과 동시 최신 모호성을 DB에서
  차단한다.
- 약관 시각(`effective_at`·`accepted_at`)은 `Asia/Seoul` 벽시계 `LocalDateTime` 계약이다. 판정·기록은
  캡처한 instant를 같은 명시적 KST 변환(`TermTimes`)으로만 바꾼다 — JVM/Clock zone에 의존하지 않는다.
- 공개 조회의 타입 필터와 순서는 클라이언트가 반복 query에 보낸 `termTypes` 배열이 권위다. DB의 `IN`
  결과 순서는 보장되지 않으므로 종류별 map을 만든 뒤 요청 배열로 재구성한다. 중복 `termTypes`는 400이다.
  필수 동의 대상 5종은 enum 속성이 아니라 `TermCatalogReadiness`가 명시하며 DB는 이 값을 복제하지 않는다.
  미지 `term_type` literal(오타 seed)과 https 절대 URI가 아닌
  `content_url`은
  `TermCatalogReadiness`가 기동 경보로 올린다(조용한 정상 취급 금지). 다만 잘못된 URL은 catalog 준비
  판정을 바꾸지 않는다 — gate 판정은 현재 필수 문서 존재 여부만 본다.
- 동의 등록은 all-or-nothing이다 — 제출 전부가 검증 시각의 현재 버전일 때만 한 DB transaction으로
  기록하고, 하나라도 미존재·stale이면 0건 기록 + 409 `-3002`다. 수락 시각은 서버가 batch당 한 번 캡처한
  KST 값이고 같은 버전 재전송은 native insert-if-absent(멱등)라 최초 수락 시각을 덮어쓰지 않는다
  (save 반복 + unique 예외 catch 금지 — rollback-only 오염 방지).
- 동의가 남아 있는 문서 행은 삭제할 수 없다(FK `ON DELETE RESTRICT`) — 이력 재구성 권위 보존.
- `/a/api` 단일 약관 gate는 controller 진입 전 interceptor에서 끝난다(미동의 403 `-3001`, S3 presign·
  외부 호출·DB/Redis write 전). 이용약관·민감정보·제3자 제공·국외 이전·위치약관을 함께 검사하고 개인정보
  처리방침은 상시 공개만 한다. "첫 1회" 판정은
  기록 존재가 아니라 해당 현재 약관 버전의 agreement 존재다 — 개정되면 현재 버전 재동의를 요구한다.
- exemption은 raw path allowlist가 아니라 `*Api` interface method의 명시적 annotation이다 — 동의
  등록/이력·내 회원 조회·회원 탈퇴 DELETE /user(#305 — 미동의 사용자도 탈퇴 가능)·push 등록
  PUT/DELETE(계정 전환 FID 재결합·로그아웃 정리)·push 수신 설정 3종·앱 초기화 GET /initializer와
  온보딩 완료 POST /onboarding/complete(#382 — 앱 온보딩은 약관 동의와 독립된 절차)만 면제하고
  bearer 인증(401)은 그대로 요구한다.
- 기대 필수 5종 중 current 문서가 하나라도 없으면 부분 강제하지 않고 단일 gate 전체를
  fail-open한다 — seed/activation 문제가 5xx나 전 회원 차단으로 이어지지 않게 하고 metric·bounded
  전이 로그로만 알린다. 로그 수위: 테이블이 완전히 빈 pre-activation 상태는 예정된 fail-open이라
  WARN(경보 소음 방지), seed 행이 존재하는 문제·ready 퇴행은 ERROR(경보 대상)다.
- 두 약관 GET response(`/api/{v}/terms`, `/a/api/{v}/terms/agreements`)는 응답에 법률 원문이 없어진
  뒤에도 privacy skeleton 대상으로 남는다 — 제목과 `contentUrl` 값은 allowlist 밖이라 마스크되고
  종류·버전만 구조 필드로 남는다.

### Authentication

- 사용자는 `(provider, provider_user_id)`로만 결합하며 email로 provider account를 merge하지 않는다.
  `ACTIVE` 행의 `provider_user_id`는 application invariant로 non-null이고 NULL은 탈퇴 행의 identity
  release뿐이다(#305).
- Kakao 재로그인은 non-null 닉네임만 갱신한다. 누락 claim은 동의 철회인지 provider 응답 누락인지
  구분할 수 없으므로 기존 값을 지우지 않는다. 갱신은 `(provider, provider_user_id, status=ACTIVE)`
  조건의 nickname-only UPDATE다 — 탈퇴와 겹친 stale 로그인이 entity 저장으로 old row의 status/released
  identity를 되살리는 경로를 만들지 않는다(영향 0행 = 갱신 폐기).
- access JWT에는 `iss/sub/iat/exp`만 두고 PII를 넣지 않는다.
- refresh token raw value는 저장하지 않고 hash만 저장한다.
- refresh rotation과 reuse detection은 transactionally 처리하고 reuse 때 그 사용자의 refresh를 모두 revoke한다.
- App Code는 hash-key Redis entry로 저장하고 GETDEL로 한 번만 소비한다.
- `/a/api`는 유효한 자체 access JWT(Bearer)가 있어야 접근한다 — 무토큰/무효 토큰은 401 `-2001`
  단일 계약으로 수렴하고, 사유·token 원문은 응답·로그에 남기지 않는다.
- `/a/api` 인증은 JWT 파싱에 더해 매 요청 users PK로 회원 `ACTIVE`를 확인한다(#305 — cache 금지).
  회원 없음과 `WITHDRAWAL_PENDING`은 구분 없이 같은 401 `-2001`이고, 상태 조회 DB 장애만 fail-closed
  500 `-500`+ERROR 관측이다(장애를 조용한 401로 숨기지 않음). userId 로그 attribute는 active 인증이
  성립한 뒤에만 기록한다.
- 탈퇴(#305, #367)는 단일 DB transaction이다 — 조건부 `ACTIVE → WITHDRAWAL_PENDING` + 탈퇴 시각 +
  `provider_user_id` NULL release + `subject_preferences.push_enabled=false` +
  `daily_notification_preferences.enabled=false` + userId-only
  PENDING 삭제 작업 insert-if-absent가 함께 commit/rollback된다(부분 상태 금지). **삭제는 하지 않는다** —
  refresh 행·push 등록(FID)·두 알림 설정 행은 모두 보존하고 발송 차단은 OFF로 표현하며, 물리 삭제는
  #302가 소유한다. 두 UPDATE의 0행은 예외로 전파돼 회원 전이·identity release·선행 UPDATE·job enqueue를
  함께 rollback한다(알림이 켜진 채 탈퇴만 접수되는 상태 금지). 동시성 판정은 조건부
  UPDATE 영향 행 수 하나다 — 승자만 정리를 수행하고, 이미 인증을 통과한 동시 요청은 202로 멱등
  수렴하며 회원 없음은 401 `-2001`이다. 202는 물리 삭제(#302)나 refresh 물리 zero가 아니라 old
  credential의 사용·연장 불가를 뜻한다.
- `WITHDRAWAL_PENDING` 행을 `ACTIVE`로 되돌리는 경로는 없다. 같은 provider의 다음 로그인은 released
  identity로 `findOrCreate` 신규 생성 경로를 타 새 userId·새 subject의 완전히 새로운 회원이 된다 —
  old subject/콘텐츠/약관 동의를 새 회원에 연결하거나 email로 병합하지 않는다.
- token 발급(app-code 교환)과 refresh 회전은 발급 전에 회원 `ACTIVE`를 조회한다(#305). 회원
  없음/탈퇴는 각각 기존 401 `-2002`(`APP_CODE_INVALID`)/`-2003`(`REFRESH_TOKEN_INVALID`, INFO)으로
  수렴하며 탈퇴 전용 code·WARN·ERROR를 만들지 않는다(WARN은 실제 verifier 불일치·active 회원 refresh
  재사용만). 검사 통과 직후 탈퇴와 겹친 in-flight 발급은 허용된 제한 예외이고 그 credential도 매 요청
  ACTIVE 검사·다음 회전 검사에서 거절된다(race로 늦게 저장된 ACTIVE refresh 행은 #302 정리 대상).
  탈퇴-회전 경합의 좁은 창(ACTIVE 검사 통과 후 claim 전에 탈퇴 commit)에서는 스퓨리어스 reuse WARN
  1회가 가능하다(문서화된 제한 예외 — 401 `-2003` 수렴 계약 자체는 동일).
- PENDING 계정 삭제 작업이 남아 있는 동안 previous HMAC key retire와 두 번째 rotation을 수행하지
  않는다(탈퇴 회원 mapping은 lazy rekey 기회가 없음). 이 gate는 지표가 아니라 secret 갱신 전 runbook의
  수동 PENDING SELECT로 확인한다(경보 미부착 지표 금지 원칙 — backlog gauge 없음).
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
