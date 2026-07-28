# Timeline Draft Runtime

## Scope

timeline draft 생성 요청부터 AI dispatch, direct-write, callback, polling, Event 편집과 cleanup까지의 runtime
sequence다.

## Read When

draft POST·polling·callback·append·Event 편집·삭제·Redis state·staging cleanup을 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineDraftTaskService`, `TimelineDraftPreparationService`, `TimelineDraftTaskPollingService`
- `TimelineAiDispatcher`(+`AiTimelineDispatchRequest`), `TimelineCallbackService`, `TimelineTaskService`
- `TimelineEventEditService`와 Event 편집 transaction service
- `DailyTimelineService`(읽기), `TimelineDeletionService`/`TimelineDeletionTransactionService`
- timeline entities(junction 포함), repositories, Redis stores and integration tests
- `src/main/resources/db/schema.sql`, `application*.properties`

## Current Implementation

### Create

1. `POST /a/api/{version}/timeline/drafts`가 요청을 받는다(유효 Bearer 필수 — 401은 security 단계 처리).
2. 인증 principal userId 하나가 record 조회·enrich photo key·staging row·Redis task owner에
   동일하게 흐른다. task는 owner를 세 상태 모두 보존한다.
3. 요청의 `recordDate`(클라 선택 날짜)와 `timelineWindow`(필수, `startTime < endTime`)를 side effect 전에
   검증한다 — 서버는 recordDate를 파생하지 않고 window를 계산·보정하지 않는다(pass-through).
4. UUIDv7 `taskId`를 만들고, SAVED record를 거부하며 기존 final `rawId`(record의
   Event→junction→Item 경로 조회)와 request 안
   중복을 제외한다. 제외 결과 신규 item이 0이면 409 `-1013`.
5. geo/photo enrich를 DB transaction 밖에서 수행한다. callback token을 만든다(원문은 dispatch body 전용,
   서버는 hash만 보관).
6. **DailyRecord 선생성 + source 저장을 한 트랜잭션으로 커밋한다**(`TimelineDraftPreparationService`):
   `(userId, recordDate)` find-or-create, 기존 DRAFT면 `recordAt/recordTimezone`을 이번 요청 값으로 즉시
   갱신, SAVED 재확인(throw → 전체 롤백), source rows 저장. 반환된 `dailyRecordId`가 task·dispatch에 실린다.
7. Redis task를 `PROCESSING`으로 저장한다(`dailyRecordId`·owner·local window·token hash·
   `processingStartedAt` — record 메타데이터는 저장하지 않는다). 저장 실패하면 이번 task의 source rows만
   보상 삭제하고 DailyRecord는 유지한다(이번 task가 처음 만든 record인지 durable하게 알 수 없고 empty
   DRAFT 재사용이 안전 — 실패 task의 empty DRAFT는 같은 날짜 재시도가 재사용하며 자동 cleanup하지 않는다).
   같은 저장 Lua가 관측 전용 전역 PROCESSING index와 사용자별 진행 작업 index
   (`timeline:draft-task:user:{userId}:processing`)에 시작 시각 score로 taskId를 추가하고 사용자 index
   key TTL을 PROCESSING TTL로 갱신하며, terminal 저장 Lua가 두 index에서 함께 제거한다. 전역 index는
   90초 초과 stuck gauge에만, 사용자 index는 진행 작업 목록 조회의 후보에만 쓰며 task 상태·소유권·
   callback 계약의 권위는 기존 JSON이다.
8. AI dispatcher를 호출한다 — body는 `taskId`·원문 `callbackToken`·`dailyRecordId`·record timezone 기반
   offset 변환 window다(계약 상세는 [ai-contract](../interfaces/ai-contract.md)). 접수(202) 확인까지 동기이며,
   접수가 확인된 경우에만 POST가 202와 `taskId`를 반환한다.
   **실패는 "미접수 확정 vs UNKNOWN"으로 내부 상태만 구분하고, 밖으로는 모두 502(`-1009`)다** —
   실패 응답에 taskId는 없고 자동 재전송도 없다. 4xx 응답(미접수 확정,
   `TimelineAiDispatchRejectedException`)은 FAILED(`-1009`, 24h) 종결을 시도하며, 그 저장까지 실패하면
   read-back·재저장 없이 상태를 불명으로 두고 같은 502로 끝낸다(500으로 전환하지 않음).
   read timeout·connect 실패·5xx·계약 불일치는 UNKNOWN이라 — AI가 이미 접수해 final write 중일 수
   있으므로 — FAILED로 덮거나 재저장(TTL 연장)하지 않고 PROCESSING을 유지한다(AI callback이 종결하거나
   task TTL 3m 만료가 회수). 502는 접수 확인 실패지 미접수 증명이 아니다.

같은 날짜의 draft, non-empty `photosToAdd` Event PATCH, Event/DailyRecord DELETE 사이에는 공통 Redis
admission guard가 없다. `timeline:date-guard:*` key는 더 이상 읽거나 쓰지 않아 배포 전에 남은 key도
작업을 막지 않고 기존 TTL로 자연 만료한다. 따라서 409 `-1016`으로 같은 날짜 작업을 선거절하지 않는다.
공통 admission과 대체 DB lock·retry·upsert가 모두 없으며, 실제 동시 경합의 graph 정합성은 별도 과제다.

`app.ai.mode=noop`은 아무 callback도 만들지 않아 task가 만료된다.
`fake`는 in-process로 final direct-write 후 실제 HTTP callback 경로를 호출하며 retry하지 않는다.
`http`는 실 AI 연동이다.

### AI direct-write and callback

1. AI가 validation → final Event/Item/junction INSERT + 채택 source DELETE를 한 transaction으로 commit한다
   (append-only — 기존 graph 불변, +10분 startAt nudge/end clamp 포함. 계약 상세는 ai-contract).
2. commit 이후에만 `Callback-Token` header + body `{status,errorCode,error}`로 알린다. 결과 graph는 body에 없다.
3. 서버 콜백은 task 조회 → token hash constant-time 검증(불일치 401 `-1002`) → Redis
   `timeline:callback-token-uses:{taskId}` marker 원자 소비 순서다. 이미 소비된 token과 terminal 재콜백은
   401 `-1012`이며 marker를 선점한 요청 하나만 이후 처리로 진행한다.
4. 소비 뒤 body status가 불량이면 400이며 terminal 저장 실패와 마찬가지로 marker를 환불하지 않는다.
   정상 요청은 Redis terminal 전이만
   기록한다(SUCCESS든 FAILED든 결과 조립·검증·저장 없음).
5. terminal 저장 성공 직후 완료 푸시를 비동기 best-effort로 예약한다(token 거절·terminal 저장 실패
   경로엔 알림 없음).
6. terminal 저장 실패는 전파된다. token은 이미 소비됐으므로 같은 token 재시도는 `-1012`이며
   PROCESSING task는 3분 TTL로 만료한다.

**수용된 MVP 한계**: commit 후 callback 전 AI process가 종료되면 살아있는 재시도 주체가 없다 — 원 task는
PROCESSING TTL로 만료되고 final graph는 commit대로 남는다. 동일 source 전량 재시도는 `-1013`이며,
일부 신규 source가 섞인 재시도의 SUCCESS 폴링이 기존 커밋분까지 반환해 실질 복구 경로가 된다.
durable receipt·redispatch는 운영 빈도가 허용 불가로 확인되는 시점에 설계한다.

### Polling and read

- `GET /a/api/{version}/timeline/daily-records`는 principal userId의 DRAFT/SAVED DailyRecord 전체를
  최신 날짜·ID 내림차순으로 반환한다(빈 record 포함, 없으면 200 `timelines=[]`).
  `GET /a/api/{version}/timeline/daily-records/{dailyRecordId}`는 `(dailyRecordId, userId)`가 일치하는
  한 건만 반환하며 없음·비소유는 404 `-404`로 은닉한다. 두 경로 모두 record→Event→junction→Item을
  한 read-only transaction에서 bulk 조회하고 Event별 `items`까지 조립한다.
- `GET /a/api/{version}/timeline/drafts`는 principal 사용자가 소유한 현재 PROCESSING taskId만 생성
  최신순(score 내림차순, 동일 ms score는 member 역 lexicographic)으로 반환한다 — 없으면 `taskIds=[]`.
  사용자 index는 후보일 뿐이며 매 조회가 후보 task JSON을 batch로 읽어 status/owner를 검증한다.
  만료(missing)·terminal·타인 소유 member는 응답에서 제외하고 요청 사용자 index에서만 best-effort
  ZREM한다(제거 실패는 유효 200을 깨지 않고 개수만 로그 — 다음 조회·terminal 전이가 재시도). owner
  누락·null·0을 포함한 역직렬화 불가 JSON은 500이며 자동 삭제하지 않는다. 목록은 lock이 아니다 —
  create/terminal/expiry와 겹치면 새 task가 이번 응답에서 빠지거나 권위 read 직후 종결된 task가 포함될
  수 있고, 각 taskId의 최신 권위는 단건 폴링이다(폴링의 404·terminal은 정상 수명주기).
- polling은 task 조회 직후, 상태 분기 전에 request userId와 task owner를 대조한다 — 타 사용자 task는
  상태와 무관하게 404 `-1001`로 은닉한다. SUCCESS 결과는 task의 `dailyRecordId`로만 조회한다 —
  (userId, recordDate) 재조회는 쓰지 않는다. record가 삭제·비소유면 404 `-404`(task 자체 없음
  `-1001`과 구분). polling 선검증 뒤 조립 서비스의
  권위 재조회 전에 record가 삭제돼도 `DRAFT_RESULT_NOT_FOUND`로 변환해 catch-all 500을 내지 않는다.
- PROCESSING polling은 `processingStartedAt` 기준 경과 완료 초를 `elapsedSeconds`로 반환한다(음수 0 clamp,
  terminal은 필드 생략). FAILED의 `body.error`는 numeric 분류 코드(`-1008`/`-1009`/`-1011`)만
  나간다. Redis writer와 reader는 JSON number만 사용한다. 누락·양수·allowlist 밖 numeric 값은
  `-1011`로 수렴하고 문자열 값은 역직렬화를 거부한다.
- 하루 타임라인 조립(`DailyTimelineService`)은 읽기 전용이며 사용자 전체도 record별 단건 반복 없이
  record/Event/junction/Item 4단계 bulk SELECT로 읽는다. Event별 Item을 junction으로 로드해
  startAt(null 먼저)·id 순으로 정렬한다. 같은 Item이 여러 Event에 연결되면 같은 `timelineItemId`가 여러
  Event의 `items`에 반복된다(응답 shape 유지 — Android 수용 확인됨).
- append 진행 중 기존 Event 상세/memo 편집은 허용한다(AI가 기존 graph를 건드리지 않기 때문).
  `photosToAdd` 유무와 관계없이 별도 날짜 admission은 없다.

### Event edit

- 기존 Event PATCH는 `title`·`subtitle`·`startAt`·`endAt` 필드 존재를 계속 요구하며 선택적
  `eventType`, `memo`, `photosToAdd`를 함께 처리한다. `memo` 부재는 변경 없음, null·blank는 제거다.
- `photosToAdd`가 없거나 빈 배열이면 기존 상세/memo 수정 transaction만 실행한다. non-empty면 입력·소유권·
  DRAFT 상태를 preflight한 뒤 별도 transaction service가 다시 소유권·DRAFT를 확인하고 Event/memo 수정 +
  PHOTO Item/junction 추가를 하나의 transaction으로 commit한다. 두 경로 모두 날짜 Redis guard를
  취득하지 않는다.
- request rawId는 입력 순서의 첫 항목을 사용한다. 같은 record의 같은 rawId가 non-PHOTO면 400, PHOTO면
  기존 Item을 재사용하고 대상 Event에 이미 연결됐으면 no-op이다. legacy PHOTO 중복은 대상 Event 연결 행을
  우선하고 없으면 가장 작은 Item ID를 고른다. 신규 후보끼리 filename이 중복되면 400이다.
- 수동 PHOTO는 client가 S3 업로드를 완료한 뒤 전달한다. 서버는 S3 object 존재 여부를 조회하지 않으며,
  payload는 `filename`·`clientPhotoUri`·좌표만 받아 `description=null`과 server-derived `photoUrl`로 저장한다.

### Delete

- Event 삭제: preflight 뒤 DB transaction에서 owner/DRAFT 재확인 → 삭제 Event에만 연결된 orphan Item
  판정 → orphan PHOTO delete-job insert와 원문 PHOTO Item 보존 → Event 삭제(junction은 FK cascade) +
  non-PHOTO orphan 명시 삭제. 날짜 Redis guard는 취득하지 않는다.
- DailyRecord 삭제: record의 Event 집합에만 연결된 orphan Item을 계산해 PHOTO job insert·원문 PHOTO
  Item 보존과 Record/Event/junction/non-PHOTO Item hard delete를 같은 commit으로 묶는다. record 밖
  Event에 연결된 후보는 방어적으로 shared 취급해 유지한다.
- 두 DELETE는 MySQL commit 뒤 S3 완료를 기다리지 않고 200을 반환한다. 현재 REST 프로세스의 환경당 단일
  worker가 oldest job 최대 1,000개를 verbose `DeleteObjects`로 처리하고 `Deleted` job과 원문 PHOTO
  Item만 한 transaction에서 최종 삭제한다. Error·응답 누락·SDK 예외는 두 행을 남겨 다음 fixed delay에
  재시도한다.

### Retention and cleanup

- PROCESSING TTL: 3분 / SUCCESS·FAILED TTL: 24시간 / callback token 소비 marker TTL: 25시간 /
  source staging retention: 7일
- PROCESSING 만료는 Redis key 소멸이지 FAILED 전이가 아니다 — scheduler 복구 없이 이후 폴링·콜백이
  404(`-1001`)로 수렴한다. 만료 전에 task 조회를 통과한 callback은 기존 terminal 전이를 완료할 수 있다.
- PROCESSING 관측 index는 terminal 전이 때 제거하고 gauge read가 3분(PROCESSING TTL)보다 오래된 고아
  member를 정리한다.
- 사용자별 진행 작업 index key는 PROCESSING 저장마다 TTL이 3분으로 갱신되고, 마지막 생성 뒤 3분
  inactivity면 통째로 만료한다(key TTL은 member별 TTL이 아님). member 회수는 terminal ZREM과 목록 조회
  lazy prune이 담당하며 별도 sweep은 없다 — 3분 미만 간격 생성이 terminal·조회 없이 계속되면 만료
  member가 누적될 수 있다(수용된 MVP trade-off).
- cleanup 대상은 만료된 source 행(omitted·FAILED task 잔여)뿐이다 — AI가 채택한 source는 final
  transaction에서 이미 삭제돼 final Item이 참조하는 S3 객체를 지울 일이 없다.
- 만료된 PHOTO source는 S3 object 삭제가 성공한 뒤 row를 삭제한다. 실패하면 row를 남겨 재시도한다.

## Invariants

- AI dispatch는 application DB transaction 안에서 기다리지 않는다(선생성 commit 후 dispatch).
- Redis SUCCESS는 AI final commit보다 먼저 기록되지 않는다(commit-then-callback + 서버는 상태 전이만).
- 서버 callback은 AI 결과 Event/Item/junction을 쓰지 않는다 — draft final write는 AI(fake 포함)가 소유한다.
  별도로 Event PATCH는 수동 PHOTO Item/junction을 서버 transaction에서 쓴다.
- callback token은 hash 검증 직후 원자 소비하고 환불하지 않는다. 최초 요청만 terminal 처리로 진행하며
  같은 token 재사용은 401 `-1012`다(at-most-once admission).
- 완료 푸시는 결과 전달 경로가 아니다 — polling이 권위 원천·유실 안전망이다(durable retry/outbox 없음).

## Known Gaps

- commit 후 callback 유실 task의 자동 복구 경로가 없다(수용된 MVP 한계 — ai-contract 참고).
- DRAFT→SAVED, emotion 설정 API가 없다.
- presign 뒤 draft가 만들어지지 않은 orphan S3 object는 cleanup하지 않는다.
- 실패 task가 남긴 empty DRAFT의 자동 cleanup은 없다(같은 날짜 재시도가 재사용).
- 같은 날짜 draft·수동 PHOTO 추가·삭제가 겹칠 때의 graph 정합성 보장은 미구현이다. 현재는 공통
  admission guard 없이 각 작업의 transaction·preflight만 유지한다.

## Update When

단계 순서, compensation, dispatch/callback 계약, junction 조회·삭제 규칙, TTL, append 또는 cleanup이
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.*'
```
