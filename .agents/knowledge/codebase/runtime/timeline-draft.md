# Timeline Draft Runtime

## Scope

timeline draft 생성 요청부터 AI staging, callback, finalize, polling과 cleanup까지의 runtime sequence다.

## Read When

draft POST·polling·callback·event grouping·append·Redis state·staging cleanup을 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineDraftTaskService`, `TimelineDraftTaskPollingService`, `TimelineEventSuggestionDispatcher`
- `TimelineCallbackService`, `TimelineEventSuggestionAssembler`,
  `TimelineEventSuggestionValidator`, `DailyTimelineService`, `TimelineTaskService`
- timeline entities, repositories, Redis stores and integration tests
- `src/main/resources/db/schema.sql`, `application*.properties`

## Current Implementation

### Create

1. `POST /a/api/{version}/timeline/drafts`가 요청을 받는다.
2. JWT user propagation이 없어 현재 `DEFAULT_USER_ID=0`을 쓴다.
3. 정오 경계로 `recordDate`를 정한다.
4. UUIDv7 `taskId`를 만들고 날짜 guard(`timeline:date-guard:{userId}:{recordDate}`)를
   holder `task:{taskId}`로 선점한다(SET NX). 실패 = 같은 날짜 작업 진행 중 → 409 `ERROR_1016`.
5. SAVED record를 거부하고, 기존 final `rawId`와 request 안 중복을 제외한다.
6. geo/photo enrich를 DB transaction 밖에서 수행한다. callback token을 만든다.
7. `timeline_draft_source_items` staging을 먼저 저장한다.
8. Redis task를 `PROCESSING`으로 저장한다. 실패하면 source staging을 보상 삭제한다.
   성공하면 dispatch 전에 guard 소유를 재확인(refresh)하며 TTL을 1시간으로 재갱신한다 —
   **소유 미확인(false/예외)이면 dispatch하지 않고 FAILED(`ERROR_1009`)로 종결한다**
   (lease 만료 후 다른 작업이 날짜를 선점했을 수 있어, 날짜당 작업 하나 불변식을 지키는 dispatch 게이트다.
   이때 guard는 내 것이 아니므로 해제하지 않는다).
9. AI dispatcher를 fire-and-forget으로 호출하고 POST는 task를 즉시 반환한다.
   dispatch 동기 실패는 FAILED 고정 후 guard를 해제한다.

guard 해제 경계: PROCESSING 저장 전 실패는 보상 후 자신의 guard만 즉시 해제(compare-and-release),
PROCESSING 저장 후 terminal 저장 실패는 해제하지 않고 TTL(1h) 만료에 맡기며,
terminal 저장 성공 시에만 해제한다. 해제는 best-effort고 TTL이 최종 안전망이지만,
재갱신(refresh)은 best-effort가 아니라 dispatch 허용 게이트다.

같은 guard를 삭제 API(`TimelineDeletionService`)도 holder `delete:{operationId}`로 선점한다 —
PROCESSING draft 진행 중 삭제와 동시 삭제를 409 `ERROR_1016`으로 차단하고, draft 생성도
삭제 진행 중이면 같은 코드로 거절된다(날짜당 작업 하나 직렬화). 삭제는 draft와 달리
성공·실패(1017/500) 모든 종료 경로에서 finally로 compare-and-release한다 — 실패 시 미해제면
클라 재시도가 TTL(1h) 동안 막혀 "재시도로 수렴" 설계가 깨진다.

`app.ai.mode=noop`은 아무 callback도 만들지 않아 task가 만료된다.
`fake`는 in-process로 staging을 기록한 뒤 실제 HTTP callback 경로를 호출하며 retry하지 않는다.

### Write then notify

1. AI가 `timeline_draft_event_suggestions`를 INSERT하고
   `timeline_draft_source_items.timeline_draft_event_suggestion_id`를 UPDATE한다.
   두 동작은 하나의 AI-side DB transaction이어야 한다.
2. staging commit 뒤 callback path의 `taskId`, `Callback-Token` header,
   body `{status,errorCode,error}`로 알린다. body에 event나 `itemIds`는 없다.
3. 서버가 staging relation을 읽고 `TimelineEventSuggestionDto.itemIds`를 내부 조립한다.
4. validator가 같은 task의 source 참조, title/time range, non-empty·non-duplicate item 배정을 검증한다.

### Finalize and polling

- finalize는 검증, Daily Record 생성/조회, Event·Item 저장과 두 staging table 삭제를 한 DB transaction으로 처리한다.
- commit 뒤 Redis task를 `SUCCESS`로 바꾸며, finalize가 반환한 `dailyRecordId`를 task에 저장한다
  (staging 부재 멱등 복구 경로도 조회한 record의 ID를 저장). AI 보고 실패와 처리한 assembly/validation
  실패는 `FAILED`와 공개 가능한 error code로 기록한다. 모든 terminal 전이 성공 직후 날짜 guard를 해제한다.
- polling은 Redis state를 읽어 PROCESSING/SUCCESS/FAILED를 반환한다. SUCCESS 결과는 task의
  `dailyRecordId`로만 조회한다 — (userId, recordDate) 재조회는 record 삭제 후 같은 날짜 재생성 시
  오조회를 만들므로 쓰지 않는다. ID가 없거나(legacy task) record가 삭제됐으면 404 `ERROR_0404`
  (task 자체 없음 `ERROR_1001`과 구분).
- 같은 날짜 append는 기존 event/item을 재그룹하지 않고 새 event만 붙인다.
- 정확히 같은 event start anchor는 +10분씩 미는 best-effort이며 DB unique constraint는 없다.
- 조정 후 end가 start보다 이르면 end를 start로 clamp한다.

### Retention and cleanup

- PROCESSING TTL: 1시간
- SUCCESS/FAILED TTL: 24시간
- callback token use state: 25시간
- staging retention: 7일
- 만료된 PHOTO source는 S3 object 삭제가 성공한 뒤 row를 삭제한다. 실패하면 row를 남겨 재시도한다.

## Invariants

- `itemIds`는 서버 내부 조립 결과이며 callback contract가 아니다.
- NULL source association은 omitted source다. non-null association은 같은 task suggestion이어야 한다.
- accepted source는 정확히 한 final event에 속한다.
- AI dispatch는 application DB transaction 안에서 기다리지 않는다.
- Redis SUCCESS는 finalize DB commit보다 먼저 기록하지 않는다.

## Known Gaps

- production AI dispatcher는 없다.
- 예상 밖 DB·인프라 exception은 callback token 소비 뒤 전파될 수 있고 task가 PROCESSING TTL까지
  남을 수 있다. 이를 자동 재시도·복구하는 경로는 없다.
- DRAFT→SAVED, emotion 설정 API가 없다.
- 요청 timezone은 검증·저장하지만 recordDate 경계 계산에는 아직 사용하지 않는다.
- presign 뒤 draft가 만들어지지 않은 orphan S3 object는 cleanup하지 않는다.

## Update When

단계 순서, compensation, callback payload/token, staging relation, validation, TTL, append 또는 cleanup이
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.*'
```
