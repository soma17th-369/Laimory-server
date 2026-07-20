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
3. 요청의 `recordDate`(클라 선택 날짜)와 `timelineWindow`(필수, `startTime < endTime`)를 side effect 전에
   검증한다 — 서버는 recordDate를 파생하지 않고 window를 계산·보정하지 않는다(pass-through).
4. UUIDv7 `taskId`를 만들고 날짜 guard(`timeline:date-guard:{userId}:{recordDate}`)를
   holder `task:{taskId}`로 선점한다(SET NX). 실패 = 같은 날짜 작업 진행 중 → 409 `ERROR_1016`.
5. SAVED record를 거부하고, 기존 final `rawId`와 request 안 중복을 제외한다.
6. geo/photo enrich를 DB transaction 밖에서 수행한다. callback token을 만든다.
7. `timeline_draft_source_items` staging을 먼저 저장한다.
8. Redis task를 `PROCESSING`으로 저장한다. 저장 직전에 `clock.instant()`로 `processingStartedAt`을
   한 번 캡처해 task에 싣는다 — 전처리(검증·enrich·staging)를 제외한 "AI 작업 대기 시작" 경계이며
   polling `elapsedSeconds`의 기준이다. 저장 실패하면 source staging을 보상 삭제한다.
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
   staging의 raw `event_type` 문자열은 이 assembler에서 `TimelineEventType`으로 변환한다 —
   null/blank/미지원 literal은 IAE로 callback `ERROR_1011` FAILED가 된다(staging entity는 외부
   writer 소유라 JPA enum 매핑을 쓰지 않는다 — 미지원 DB 문자열의 hydration 예외가 FAILED
   변환 경계를 우회하는 것을 막는 D1-A 설계).
4. validator가 같은 task의 source 참조, eventType/title/time range, non-empty·non-duplicate item 배정을 검증한다.

### Finalize and polling

- finalize는 검증, Daily Record 생성/조회, Event·Item 저장과 두 staging table 삭제를 한 DB transaction으로 처리한다.
- commit 뒤 Redis task를 `SUCCESS`로 바꾸며, finalize가 반환한 `dailyRecordId`를 task에 저장한다
  (staging 부재 멱등 복구 경로도 조회한 record의 ID를 저장). AI 보고 실패와 처리한 assembly/validation
  실패는 `FAILED`와 공개 가능한 error code로 기록한다. 모든 terminal 전이 성공 직후 날짜 guard를 해제한다.
- polling은 Redis state를 읽어 PROCESSING/SUCCESS/FAILED를 반환한다. SUCCESS 결과는 task의
  `dailyRecordId`로만 조회한다 — (userId, recordDate) 재조회는 record 삭제 후 같은 날짜 재생성 시
  오조회를 만들므로 쓰지 않는다. ID가 없거나(legacy task) record가 삭제됐으면 404 `ERROR_0404`
  (task 자체 없음 `ERROR_1001`과 구분).
- PROCESSING polling은 `processingStartedAt` 기준 경과 완료 초를 `elapsedSeconds`로 함께 반환한다
  (음수는 0 clamp). terminal 전이는 이 시각을 보존하지 않고 폐기하므로 SUCCESS/FAILED 응답에는
  필드가 없고, 배포 전 legacy PROCESSING task(시각 부재, TTL 1h 내 최대 그만큼 혼재)도 필드를 생략한다.
- suggestion의 `eventType`은 재추론 없이 final Event로 복사되고, SUCCESS polling·Event 수정·memo
  응답의 공용 `TimelineEventResponse.eventType`으로 노출된다. Event 상세 PATCH는 optional
  `eventType` 키로 분류를 바꿀 수 있다(누락=유지, 명시적 null·미지원 literal=400).
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
