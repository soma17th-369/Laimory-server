# AI Dispatch and Callback Contract

## Scope

API server와 AI 측 사이의 draft task dispatch(HTTP), MySQL direct-write와 callback 계약이다.

## Read When

AI dispatcher, source staging, dispatch body, final write 계약, callback body/header를 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineAiDispatcher` implementations (`NoOp`/`Fake`/`Http`) and `AiTimelineDispatchRequest`/`Response`
- `TimelineDraftSourceItem`, junction/final entities, repositories and schema
- `FakeAiTimelineAppendService` (fake direct-write — 실 AI final transaction 계약의 in-process 대행)
- `TimelineCallbackApi`, `DraftTaskCallbackRequest`, `TimelineCallbackService`
- `HttpTimelineAiDispatcherTest`(request/response contract fixture), fake unit/wiring tests,
  `TimelineCallbackTokenIntegrationTest`

## Contract

이 계약은 dispatch → direct-write → callback 세 단계다. AI는 Redis를 읽지 않는다 —
task 입력은 dispatch body로 받고 source는 MySQL에서 직접 읽는다.

### 1. Dispatch (API → AI)

```http
POST {base-url}/v1/timeline
Content-Type: application/json
```

```json
{
  "taskId": "...",
  "callbackToken": "...",
  "dailyRecordId": 42,
  "window": {"startAt": "2026-07-22T00:00:00+09:00", "endAt": "2026-07-23T00:00:00+09:00"}
}
```

- 네 필드 모두 필수. `callbackToken`은 task별 원문으로 이 body로만 한 번 전달된다(로그·MySQL·Redis 저장
  금지 — 서버는 hash만 보관). `window`는 Android local window를 DailyRecord의 검증된 `record_timezone`으로
  offset ISO-8601 변환한 값이다(포맷 `yyyy-MM-dd'T'HH:mm:ssXXX` — fixture로 고정).
- source item은 body로 보내지 않는다 — AI가 `taskId`로 `timeline_draft_source_items`를 읽는다.
  `recordDate`/`recordAt`/`recordTimezone`/`userId`도 반복하지 않는다(AI가 `dailyRecordId`로 DB에서 읽음).
- 접수 성공은 `202 Accepted` + body `{"taskId": <동일>, "status": "PROCESSING"}` — final 성공이 아니다.
  dispatcher는 202·동일 taskId·PROCESSING을 검증하고 불일치·비202·타임아웃은 던져서 task를 FAILED(1009)로
  종결한다. 필수 필드 누락·offset 파싱 실패는 FastAPI 표준 422다.
- 현재 AI endpoint는 무인증이다(private network 전제) — production 전 service authentication 추가 시
  request header와 fixture를 양 저장소에서 함께 갱신한다.

### 2. AI direct-write (final transaction)

AI는 inference를 DB transaction 밖에서 수행하고, 아래 final write만 하나의 짧은 transaction으로 commit한다.

validation(위반 시 final row 없이 FAILED callback):

- `dailyRecordId` 존재·DRAFT·source 전 행의 `user_id`가 record owner와 일치
- task source 1건 이상, `(task_id, raw_id)` 중복 없음, 생성 Event 1건 이상·Event마다 source 1건 이상
- Event type allowlist(`TimelineEventType` uppercase literal — 판별 불가면 `UNKNOWN` 명시),
  non-blank title, start 필수, end >= start
- 이 record의 기존 final Item(Event→junction 경유)에 같은 rawId가 없음을 write 직전 재검사
- 기존 Event/Item/junction/memo를 update/delete하지 않음(append-only)

write(같은 transaction):

1. DailyRecord를 ID로 lock·owner/status 재검사(날짜 fallback 조회 금지)
2. 기존 Event startAt과 정확히 겹치면 +10분씩 nudge, end < start면 start로 clamp
3. 채택 source rawId마다 `timeline_items` 정확히 한 행 INSERT(distinct 처리 — 여러 Event 공유 시에도 1행),
   새 `timeline_events` INSERT, `timeline_event_items` junction INSERT
4. 채택된 source row만 DELETE(omitted는 retention cleanup에 맡김)
5. 감사 컬럼: timestamp는 DB default로 생략 가능, `modified_by`는 `'AI'` 명시

새 Item은 현재 task의 새 Event에만 연결한다(same-DailyRecord 규칙 — DB 제약이 아닌 writer 계약).
MySQL `DATETIME`은 offset을 보존하지 않으므로 offset 입력은 record timezone의 wall-clock으로 정규화해 저장한다.

### 3. Callback notification

commit **이후에만** 다음 값으로 알린다(commit-then-callback).

- path: task ID가 포함된 server API callback (`/s/api/{v}/timeline/drafts/{taskId}/callback`)
- header: `Callback-Token` — dispatch body로 받은 원문을 그대로 반환
- body: `status`, `errorCode`, `error` — SUCCESS에도 event/item 결과는 없다(서버는 상태 전이만 기록)

기준 AI 구현은 callback을 한 번 POST하고 실패를 log/반환할 뿐 자동 재시도하지 않는다. 서버는 task 조회와
constant-time hash 검증 직후 Redis marker를 `SET NX`로 소비하며, 최초 요청 하나만 terminal 처리로 진행한다.
같은 유효 token의 동시·순차 재사용은 401 `ERROR_1012`다.

## Callback Authentication

- raw token은 dispatch body로 AI에만 전달한다.
- Redis에는 SHA-256 hash만 저장하고 비교는 constant-time으로 한다.
- task 없음은 404 `ERROR_1001`, token 누락·불일치는 401 `ERROR_1002`이며 이 경로에서는 소비하지 않는다.
- hash 검증 직후 `timeline:callback-token-uses:{taskId}`=`used` marker를 25시간 TTL로 원자 선점한다.
  선점 실패와 terminal 재콜백은 401 `ERROR_1012`이며 terminal 저장·guard 해제·push에 도달하지 않는다.
- token은 인증 시점에 소비한다. 이후 owner/body 검증이나 terminal 저장이 실패해도 marker를 삭제·환불하지
  않으므로 같은 token으로 재시도할 수 없다(at-most-once admission, exactly-once processing 아님).

## Failure Semantics

- AI가 FAILED를 알리면 허용된 public error code(현재 `ERROR_1008`)만 task state에 기록한다.
  자유 text `error`는 진단 log에만 남기고 polling 응답에는 저장·노출하지 않는다.
- DB commit 뒤에만 task를 SUCCESS로 바꾼다. commit 후 callback 전 AI process가 종료되면 살아있는 재시도
  주체가 없다 — 원 task는 PROCESSING TTL로 만료되고 final graph는 commit대로 남는다(수용된 MVP 한계 —
  동일 source 전량 재시도는 `ERROR_1013`, 일부 신규 source 재시도가 실질 복구 경로).
- callback token 소비 뒤 terminal 저장이 실패해도 같은 token을 재사용하지 않는다. PROCESSING task와 날짜
  guard는 1시간 TTL이 회수하며 durable receipt·redispatch는 별도 복구 과제다.
- callback 성공은 application envelope 없이 body 없는 HTTP 200이다.
  400/401/404 error는 `GlobalExceptionHandler`의 application envelope를 사용한다.

## Current Implementations

- `noop`(기본): dispatch하지 않아 PROCESSING task가 TTL로 만료된다.
- `fake`(dev): in-process로 direct-write(`FakeAiTimelineAppendService`) 후 실제 HTTP callback 경로를
  한 번 호출한다. callback retry는 없다.
- `http`: 실 AI 연동 — `app.ai.http.base-url` 필수, 접수 타임아웃(connect 2s/read 5s 기본) 초과는 FAILED.

## Invariants

- dispatch body 필드명·시각 포맷은 AI 규격이 명명 권위인 공개 계약이다 —
  `HttpTimelineAiDispatcherTest`가 fixture로 고정한다.
- AI direct-write transaction과 callback 순서를 뒤집지 않는다(commit-then-callback).
- callback body에 결과 graph를 추가하지 않는다.
- durable receipt·startup scan·같은 taskId 자동 redispatch를 추가하지 않는다(재설계 시점은 운영 빈도 확인 후).
- 실제 credential이나 callback token 값을 문서·log에 기록하지 않는다.

## Known Gaps

- AI endpoint service authentication 미구현(production 전 필수 — 이슈 #181).
- commit 후 callback 유실 task의 자동 복구 경로가 없다(수용된 MVP 한계).

## Update When

dispatch shape/endpoint, direct-write validation·transaction 계약, callback header/body, 인증 또는
failure semantics가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.HttpTimelineAiDispatcherTest' \
  --tests 'com.laimory.server.timeline.service.AiDispatcherWiringTest' \
  --tests 'com.laimory.server.timeline.service.Fake*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.service.TimelineCallbackTokenIntegrationTest'
```
