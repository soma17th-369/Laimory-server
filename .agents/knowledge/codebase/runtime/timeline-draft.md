# Timeline Draft Runtime

## Scope

timeline draft 생성 요청부터 AI dispatch, direct-write, callback, polling과 cleanup까지의 runtime sequence다.

## Read When

draft POST·polling·callback·append·삭제·Redis state·staging cleanup을 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineDraftTaskService`, `TimelineDraftPreparationService`, `TimelineDraftTaskPollingService`
- `TimelineAiDispatcher`(+`AiTimelineDispatchRequest`), `TimelineCallbackService`, `TimelineTaskService`
- `DailyTimelineService`(읽기), `TimelineDeletionService`/`TimelineDeletionTransactionService`
- timeline entities(junction 포함), repositories, Redis stores and integration tests
- `src/main/resources/db/schema.sql`, `application*.properties`

## Current Implementation

### Create

1. `POST /a/api/{version}/timeline/drafts`가 요청을 받는다(유효 Bearer 필수 — 401은 security 단계 처리).
2. 인증 principal userId 하나가 record 조회·guard·enrich photo key·staging row·Redis task owner에
   동일하게 흐른다. task는 owner를 세 상태 모두 보존한다.
3. 요청의 `recordDate`(클라 선택 날짜)와 `timelineWindow`(필수, `startTime < endTime`)를 side effect 전에
   검증한다 — 서버는 recordDate를 파생하지 않고 window를 계산·보정하지 않는다(pass-through).
4. UUIDv7 `taskId`를 만들고 날짜 guard(`timeline:date-guard:{userId}:{recordDate}`)를
   holder `task:{taskId}`로 선점한다(SET NX). 실패 = 같은 날짜 작업 진행 중 → 409 `ERROR_1016`.
5. SAVED record를 거부하고, 기존 final `rawId`(record의 Event→junction→Item 경로 조회)와 request 안
   중복을 제외한다. 제외 결과 신규 item이 0이면 409 `ERROR_1013`.
6. geo/photo enrich를 DB transaction 밖에서 수행한다. callback token을 만든다(원문은 dispatch body 전용,
   서버는 hash만 보관).
7. **DailyRecord 선생성 + source 저장을 한 트랜잭션으로 커밋한다**(`TimelineDraftPreparationService`):
   `(userId, recordDate)` find-or-create, 기존 DRAFT면 `recordAt/recordTimezone`을 이번 요청 값으로 즉시
   갱신, SAVED 재확인(throw → 전체 롤백), source rows 저장. 반환된 `dailyRecordId`가 task·dispatch에 실린다.
8. Redis task를 `PROCESSING`으로 저장한다(`dailyRecordId`·owner·local window·token hash·
   `processingStartedAt` — record 메타데이터는 저장하지 않는다). 저장 실패하면 이번 task의 source rows만
   보상 삭제하고 DailyRecord는 유지한다(이번 task가 처음 만든 record인지 durable하게 알 수 없고 empty
   DRAFT 재사용이 안전 — 실패 task의 empty DRAFT는 같은 날짜 재시도가 재사용하며 자동 cleanup하지 않는다).
   성공하면 dispatch 전에 guard 소유를 재확인(refresh)하며 TTL을 1시간으로 재갱신한다 —
   **소유 미확인(false/예외)이면 dispatch하지 않고 FAILED(`ERROR_1009`)로 종결한다.**
9. AI dispatcher를 호출한다 — body는 `taskId`·원문 `callbackToken`·`dailyRecordId`·record timezone 기반
   offset 변환 window다(계약 상세는 [ai-contract](../interfaces/ai-contract.md)). 접수(202) 확인까지 동기다.
   **실패는 "미접수 확정 vs UNKNOWN"으로 분류한다**: 4xx 응답(미접수 확정, `TimelineAiDispatchRejectedException`)만
   FAILED(`ERROR_1009`) 종결 + guard 해제한다. read timeout·connect 실패·5xx·계약 불일치는 UNKNOWN이라 —
   AI가 이미 접수해 final write 중일 수 있으므로 — FAILED로 확정하지 않고 PROCESSING·guard를 유지한다
   (AI callback이 종결하거나 TTL 1h 만료가 회수). FAILED로 확정하면 커밋된 결과와 어긋나고 이후 AI write가
   새 draft/삭제와 겹칠 수 있다.

guard 해제 경계: PROCESSING 저장 전 실패는 보상 후 자신의 guard만 즉시 해제(compare-and-release),
PROCESSING 저장 후 terminal 저장 실패는 해제하지 않고 TTL(1h) 만료에 맡기며,
terminal 저장 성공 시에만 해제한다. 해제는 best-effort고 TTL이 최종 안전망이지만,
재갱신(refresh)은 best-effort가 아니라 dispatch 허용 게이트다.

같은 guard를 삭제 API(`TimelineDeletionService`)도 holder `delete:{operationId}`로 선점한다 —
PROCESSING draft 진행 중 삭제와 동시 삭제를 409 `ERROR_1016`으로 차단하고, draft 생성도
삭제 진행 중이면 같은 코드로 거절된다(날짜당 작업 하나 직렬화). 삭제는 draft와 달리
성공·실패(1017/500) 모든 종료 경로에서 finally로 compare-and-release한다.

`app.ai.mode=noop`은 아무 callback도 만들지 않아 task가 만료된다.
`fake`는 in-process로 final direct-write 후 실제 HTTP callback 경로를 호출하며 retry하지 않는다.
`http`는 실 AI 연동이다.

### AI direct-write and callback

1. AI가 validation → final Event/Item/junction INSERT + 채택 source DELETE를 한 transaction으로 commit한다
   (append-only — 기존 graph 불변, +10분 startAt nudge/end clamp 포함. 계약 상세는 ai-contract).
2. commit 이후에만 `Callback-Token` header + body `{status,errorCode,error}`로 알린다. 결과 graph는 body에 없다.
3. 서버 콜백은 token 검증(401 `ERROR_1002`) → terminal이면 멱등 no-op 200(AI at-least-once 재시도 흡수 —
   token-use 카운터 없음) → owner·dailyRecordId 없는 legacy task는 404 fail-closed → Redis terminal 전이만
   기록한다(SUCCESS든 FAILED든 결과 조립·검증·저장 없음).
4. terminal 저장 성공 직후 task의 `dailyRecordId`로 DailyRecord를 조회해 recordDate를 얻어 날짜 guard를
   compare-and-release한다(record 없음/owner 불일치면 추정하지 않고 TTL에 맡김). 그 뒤 완료 푸시를
   비동기 best-effort로 예약한다(멱등 단축·토큰 거절·terminal 저장 실패 경로엔 알림 없음).
5. terminal 저장 실패는 guard를 풀지 않고 전파된다 — AI의 콜백 재시도가 멱등 게이트를 통과해 전이를
   복구한다.

**수용된 MVP 한계**: commit 후 callback 전 AI process가 종료되면 살아있는 재시도 주체가 없다 — 원 task는
PROCESSING TTL로 만료되고 final graph는 commit대로 남는다. 동일 source 전량 재시도는 `ERROR_1013`이며,
일부 신규 source가 섞인 재시도의 SUCCESS 폴링이 기존 커밋분까지 반환해 실질 복구 경로가 된다.
durable receipt·redispatch는 운영 빈도가 허용 불가로 확인되는 시점에 설계한다.

### Polling and read

- `GET /a/api/{version}/timeline/daily-records`는 principal userId의 DRAFT/SAVED DailyRecord 전체를
  최신 날짜·ID 내림차순으로 반환한다(빈 record 포함, 없으면 200 `timelines=[]`).
  `GET /a/api/{version}/timeline/daily-records/{dailyRecordId}`는 `(dailyRecordId, userId)`가 일치하는
  한 건만 반환하며 없음·비소유는 404 `ERROR_0404`로 은닉한다. 두 경로 모두 record→Event→junction→Item을
  한 read-only transaction에서 bulk 조회하고 Event별 `items`까지 조립한다.
- polling은 task 조회 직후, 상태 분기 전에 request userId와 task owner를 대조한다 — 타 사용자·
  owner 없는 legacy task는 상태와 무관하게 404 `ERROR_1001`로 은닉한다. SUCCESS 결과는 task의
  `dailyRecordId`로만 조회한다 — (userId, recordDate) 재조회는 쓰지 않는다. ID가 없거나(legacy) record가
  삭제·비소유면 404 `ERROR_0404`(task 자체 없음 `ERROR_1001`과 구분). polling 선검증 뒤 조립 서비스의
  권위 재조회 전에 record가 삭제돼도 `DRAFT_RESULT_NOT_FOUND`로 변환해 catch-all 500을 내지 않는다.
- PROCESSING polling은 `processingStartedAt` 기준 경과 완료 초를 `elapsedSeconds`로 반환한다(음수 0 clamp,
  terminal·legacy는 필드 생략). FAILED의 `body.error`는 분류 코드(`ERROR_1008`/`1009`/`1011`)만 나간다.
- 하루 타임라인 조립(`DailyTimelineService`)은 읽기 전용이며 사용자 전체도 record별 단건 반복 없이
  record/Event/junction/Item 4단계 bulk SELECT로 읽는다. Event별 Item을 junction으로 로드해
  startAt(null 먼저)·id 순으로 정렬한다. 같은 Item이 여러 Event에 연결되면 같은 `timelineItemId`가 여러
  Event의 `items`에 반복된다(응답 shape 유지 — Android 수용 확인됨).
- append 진행 중 기존 Event 상세/memo 편집은 허용한다(AI가 기존 graph를 건드리지 않기 때문).

### Delete

- Event 삭제: guard 선점 → 삭제 Event에만 연결된 exclusive Item 판정(junction) → exclusive PHOTO만 S3
  배치 삭제 → DB 트랜잭션에서 재확인 후 Event 삭제(junction은 FK cascade) + orphan Item 명시 삭제.
  다른 Event에도 연결된 shared Item/PHOTO는 유지한다.
- DailyRecord 삭제: record의 Event 집합에만 연결된 Item이 exclusive다. record 밖 Event에 연결된 후보는
  방어적으로 shared 취급해 유지한다(정상 write 경로엔 없어야 하는 상태). record 삭제로 events/junction이
  cascade되고 orphan Item은 명시 삭제된다.

### Retention and cleanup

- PROCESSING TTL: 1시간 / SUCCESS·FAILED TTL: 24시간 / source staging retention: 7일
- cleanup 대상은 만료된 source 행(omitted·FAILED task 잔여)뿐이다 — AI가 채택한 source는 final
  transaction에서 이미 삭제돼 final Item이 참조하는 S3 객체를 지울 일이 없다.
- 만료된 PHOTO source는 S3 object 삭제가 성공한 뒤 row를 삭제한다. 실패하면 row를 남겨 재시도한다.

## Invariants

- AI dispatch는 application DB transaction 안에서 기다리지 않는다(선생성 commit 후 dispatch).
- Redis SUCCESS는 AI final commit보다 먼저 기록되지 않는다(commit-then-callback + 서버는 상태 전이만).
- 서버는 final Event/Item/junction을 쓰지 않는다 — final write는 AI(fake 포함)가 소유한다.
- 유효한 재콜백은 terminal no-op 200이다(멱등) — 재시도를 막는 소비 게이트를 다시 넣지 않는다.
- 완료 푸시는 결과 전달 경로가 아니다 — polling이 권위 원천·유실 안전망이다(durable retry/outbox 없음).

## Known Gaps

- commit 후 callback 유실 task의 자동 복구 경로가 없다(수용된 MVP 한계 — ai-contract 참고).
- DRAFT→SAVED, emotion 설정 API가 없다.
- presign 뒤 draft가 만들어지지 않은 orphan S3 object는 cleanup하지 않는다.
- 실패 task가 남긴 empty DRAFT의 자동 cleanup은 없다(같은 날짜 재시도가 재사용).

## Update When

단계 순서, compensation, dispatch/callback 계약, junction 조회·삭제 규칙, TTL, append 또는 cleanup이
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.*'
```
