# AI Staging and Callback Contract

## Scope

API server와 AI 측 사이의 draft task, MySQL staging과 callback 계약이다.

## Read When

AI dispatcher, staging table, callback body/header, assembler, validator 또는 finalize를 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineAiDispatcher` implementations and dispatch DTOs
- `TimelineDraftSourceItem`, `TimelineDraftEventSuggestion`, repositories and schema
- `TimelineEventSuggestionAssembler`, `TimelineEventSuggestionDto`,
  `TimelineEventSuggestionValidator`
- `TimelineCallbackApi`, `DraftTaskCallbackRequest`, `TimelineCallbackService`
- fake AI and callback integration/E2E tests

## Contract

이 계약은 write-then-notify 세 단계다.

### 1. AI staging write

AI는 같은 task에 대해 하나의 DB transaction에서:

- `timeline_draft_event_suggestions`에 event suggestion을 INSERT한다.
- 채택한 `timeline_draft_source_items`의
  `timeline_draft_event_suggestion_id` association을 UPDATE한다.

NULL association은 omitted source다. non-null 값은 같은 task의 suggestion을 가리켜야 한다.

`timeline_draft_event_suggestions.event_type`이 Event 분류의 AI 출력 권위 필드다.

- AI는 `TimelineEventType`의 uppercase literal(`WAKE_UP`, `SLEEP`, `MOVEMENT`, `CALENDAR_EVENT`,
  `MEAL`, `PHOTO_MOMENT`, `MEETING`, `CLASS`, `WORK`, `EXERCISE`, `SOCIAL`, `REST`, `UNKNOWN`)
  중 하나를 INSERT한다. 판별 불가면 `UNKNOWN`을 명시한다.
- 컬럼을 생략하면 DB default `'UNKNOWN'`이 채워진다(구버전 writer 호환). null/blank/미지원
  literal은 서버 assembler 변환 실패로 그 task가 FAILED가 된다.
- 새 literal 활성화 순서는 "Server enum 배포 → AI writer 활성화"다 — AI가 먼저 새 값을 쓰면
  해당 task는 validation FAILED다.
- 서버는 title·source item 조합으로 타입을 재추론하지 않고 staging 값을 그대로 final에 전달한다.

### 2. Callback notification

staging commit 뒤 다음 값으로 알린다.

- path: task ID가 포함된 server API callback
- header: `Callback-Token`
- body: `status`, `errorCode`, `error`

SUCCESS body에도 event, source item, `itemIds`가 없다.

### 3. Server assembly and finalize

서버는 staging table을 읽고 association별 source PK를 모아 내부
`TimelineEventSuggestionDto.itemIds`를 만든다. 조립 전에 모든 staging row의 `user_id`가
Redis task owner와 같은지 먼저 검증한다(불일치 = finalize 없이 FAILED). validator가
참조·중복·필수값·시간 범위를 검증한 후 Daily Record, Timeline Events와 Timeline Items를 finalize한다.
task에 owner가 없으면(배포 전 legacy) token 검증·소비 뒤 finalize 없이 404로 fail-closed한다.

## Callback Authentication

- raw token은 task dispatch 때 AI에만 전달한다.
- Redis에는 SHA-256 hash와 use state만 저장한다.
- 비교는 constant-time으로 하고 use limit을 atomic하게 소비한다.
- token 누락·불일치와 이미 소비된 token은 서로 다른 공개 error code로 처리한다.

## Failure Semantics

- AI가 FAILED를 알리면 허용된 public error code만 task state에 기록한다.
  자유 text `error`는 진단 log에만 남기고 polling 응답에는 저장·노출하지 않는다.
- assembly·validation에서 `IllegalArgumentException`/`IllegalStateException`이 나면 finalize DB 변경은
  rollback되고 task를 FAILED로 바꾼다.
- 예상 밖 DB·인프라 exception은 callback 밖으로 전파된다. 이때 token은 이미 소비됐고 task가
  PROCESSING으로 TTL까지 남을 수 있다.
- DB finalize commit 뒤에만 task를 SUCCESS로 바꾼다.
- callback 성공은 application envelope 없이 body 없는 HTTP 200이다.
  400/401/404 error는 `GlobalExceptionHandler`의 application envelope를 사용한다.

## Current Implementations

- `noop`: dispatch하지 않아 PROCESSING task가 TTL로 만료된다.
- `fake`: staging write 뒤 실제 HTTP callback 경로를 호출한다. retry는 없다.
- production external AI dispatcher: 미구현.

## Invariants

- Redis PROCESSING task의 `timelineWindow`는 클라이언트 요청값 pass-through다 — 서버가 source item
  min/max로 재계산하지 않는다. AI가 읽는 field name과 compact 포맷(`yyyyMMdd'T'HHmmss`)은 유지된다.
- Redis task JSON에는 owner `userId`(숫자)가 additive field로 기존 필드 뒤에 붙는다 — AI writer는
  이 필드를 몰라도 되지만(관대 수용), staging INSERT의 `user_id`는 반드시 소비하는 task의 source row와
  같은 사용자여야 한다(서버가 조립 전 검증해 불일치는 FAILED).
- callback에 `itemIds`를 다시 추가하지 않는다. 내부 DTO에서는 제거하지 않는다.
- AI staging transaction과 callback 순서를 뒤집지 않는다.
- source association을 request index로 해석하지 않는다. 값은 server staging PK다.
- 실제 credential이나 callback token 값을 문서·log에 기록하지 않는다.

## Known Gaps

- external production AI adapter, retry/idempotent delivery policy와 운영 runbook이 없다.
- 예상 밖 finalize 인프라 failure를 FAILED로 종결하거나 안전하게 재시도하는 복구 경로가 없다.

## Update When

staging schema/ownership, association, dispatch shape, callback header/body, token use, assembly,
validation 또는 failure semantics가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.TimelineEventSuggestion*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.FakeAiDispatcherEndToEndIntegrationTest'
```
