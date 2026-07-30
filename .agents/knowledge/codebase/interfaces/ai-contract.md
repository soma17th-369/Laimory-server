# AI Dispatch, Input, Result and Callback Contract

## Scope

API 서버와 AI 사이의 dispatch → 입력 조회 → 결과 저장 → 상태 callback HTTP 계약이다. AI는 MySQL·Redis에
직접 접근하지 않는다.

## Read When

AI dispatcher, 서버간 입력·결과 endpoint, task token, Redis task stage, 결과 저장 transaction 또는
callback body를 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineAiDispatcher` implementations와 dispatch DTO
- `TimelineAiTaskApi`, 입력·결과 DTO와 service
- `TimelineCallbackApi`, callback DTO와 service
- `TaskTokens`, `TaskStage`, `TimelineDraftTask`, `TimelineTaskStore`
- `TimelineAiTaskFlowIntegrationTest`

## Contract

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
  "window": {
    "startAt": "2026-07-22T00:00:00+09:00",
    "endAt": "2026-07-23T00:00:00+09:00"
  }
}
```

- 기존 AI dispatch 계약의 네 필드와 offset ISO-8601 포맷을 유지한다. source item은 싣지 않고 AI가 입력
  조회 API로 가져간다.
- `callbackToken`은 외부 계약의 기존 필드명이며, 내부에서는 세 서버간 단계를 모두 인증하는 256-bit
  난수 bearer token이다. API 서버는 Redis task에 SHA-256 hash만 저장한다.
- 접수 성공은 `202 Accepted` + `{"taskId":<동일>,"status":"PROCESSING"}`다.
- dispatcher는 4xx만 미접수 확정으로 분류한다. 비202·계약 불일치·타임아웃·5xx·전송 실패는 접수 불명이라
  PROCESSING을 유지하고 draft POST는 502 `-1009`로 끝난다.
- AI endpoint 자체 service authentication은 아직 없다(private network 전제).

### 2. 단일 Task Token과 Redis Stage

입력 조회·결과 저장·callback은 모두 같은 header를 쓴다.

```http
Task-Token: <dispatch가 받은 callbackToken>
```

- 매 요청은 task 조회 직후 제시 token의 hash와 Redis `tokenHash`를 constant-time 비교한다.
- `/s/api`에는 request principal이 없다. token 소유가 해당 task에 대한 capability다.
- 원문 token은 저장·로그하지 않는다.
- 별도 소비 marker나 token rotation은 없다. 호출 순서는 PROCESSING task의 내부 `TaskStage`가 제한한다.

```text
INPUT_PENDING
  → RESULT_PENDING
  → RESULT_WRITING
  → CALLBACK_PENDING
  → SUCCESS
```

- stage 전이와 terminal 전이는 현재 task JSON 전체를 기대값으로 비교하는 Redis Lua CAS다.
- 외부 polling 상태는 계속 `PROCESSING`/`SUCCESS`/`FAILED`만 노출한다.

### 3. 입력 조회 (AI → API)

```http
GET /s/api/{version}/timeline/drafts/{taskId}/input
Task-Token: <taskToken>
```

응답:

- `taskId`, `recordDate`, `recordTimeZone`
- `window.startAt`/`endAt`
- `sourceItems[]`: `rawId`, `itemType`, nullable `startAt`/`endAt`, `payload`

`userId`·`dailyRecordId`·행 PK와 다음 token은 응답하지 않는다.

처리 규칙:

- token·PROCESSING·stage 검증을 개인 데이터 조회보다 먼저 한다.
- `INPUT_PENDING`과 응답 유실 재시도를 위한 `RESULT_PENDING`에서만 허용한다.
- 최초 응답 조립 뒤 `INPUT_PENDING → RESULT_PENDING`, 재조회는 같은 stage에서 TTL만 갱신한다.
- 시각은 record timezone 기준 offset ISO-8601이다.

### 4. 결과 저장 (AI → API)

```http
POST /s/api/{version}/timeline/drafts/{taskId}/result
Task-Token: <taskToken>
```

```json
{"events":[{"eventType":"MEAL","title":"...","subtitle":null,
            "startAt":"...","endAt":null,"sourceRawIds":["..."]}]}
```

- body 없는 200이다. 다음 callback token을 반환하지 않는다.
- `RESULT_PENDING` 요청 하나만 CAS로 `RESULT_WRITING`을 선점해 MySQL transaction을 실행한다.
- 저장 성공 뒤 `CALLBACK_PENDING`으로 전이한다.
- `CALLBACK_PENDING` 재요청은 graph를 다시 쓰지 않고 200이다.
- `RESULT_WRITING` 중복 요청과 다른 stage 요청은 409 `-1017`이다.

검증:

- event와 event별 source가 각각 1건 이상
- `eventType`, non-blank `title`, `startAt`, `endAt >= startAt`, DB 문자열 길이
- 모든 `sourceRawId`가 이 task staging source에 존재
- 같은 record final graph에 채택 rawId가 아직 없음

MySQL transaction은 DB 검증, Event/Item/junction INSERT와 채택 source DELETE를 함께 commit한다.
offset 시각은 record timezone wall-clock으로 정규화하고 start 충돌은 +10분 nudge, end는 start 이상으로
clamp한다.

저장 예외가 호출부까지 돌아오면 가능한 경우 `RESULT_WRITING → RESULT_PENDING`으로 복구한다.

### 5. 상태 Callback (AI → API)

```http
POST /s/api/{version}/timeline/drafts/{taskId}/callback
Task-Token: <taskToken>
```

- body는 `status`, `errorCode`, `error`뿐이다.
- SUCCESS는 `CALLBACK_PENDING`에서만 허용한다.
- FAILED는 결과 저장 전인 `INPUT_PENDING`/`RESULT_PENDING`에서만 허용한다.
- terminal task의 같은 callback 재전송은 200, 상충 결과는 409 `-1017`이다.
- terminal CAS에 처음 성공한 요청만 완료 push를 예약한다.
- FAILED `errorCode`는 음수 JSON integer이며 미지 값은 `-1008`로 수렴한다. 자유 text `error`는 로그로만
  남긴다.

## Failure Semantics

- Redis와 MySQL을 분산 transaction으로 묶지 않는다.
- `RESULT_WRITING` 선점 뒤 프로세스가 종료되면 task는 그 stage에서 PROCESSING TTL로 만료될 수 있다.
- MySQL commit 뒤 `CALLBACK_PENDING` 전이 전 장애면 graph는 남지만 task 완료 상태는 잃을 수 있다.
- 이 경로의 receipt, reconciliation, 자동 callback은 두지 않는 것이 수용된 MVP 한계다.
- task 만료 뒤 어떤 서버간 요청도 404 `-1001`이며 task를 부활시키지 않는다.
- PROCESSING TTL은 3분이고 입력 조회·stage 전이마다 다시 확보한다. terminal TTL은 24시간이다.

## Current Implementations

- `noop`: dispatch하지 않아 PROCESSING task가 TTL로 만료된다.
- `fake`: 동일 task token으로 자기 서버의 입력 → 결과 → callback endpoint를 실제 HTTP 호출한다.
- `http`: 실 AI 연동. 접수 timeout과 응답 계약을 검증한다.

## Invariants

- dispatch body 필드명은 AI 규격이 권위다.
- 서버간 모든 단계는 같은 task token을 쓰고 Redis에는 hash만 저장한다.
- 입력과 결과의 stage 전이는 Redis task JSON CAS다.
- 결과 graph와 채택 source 삭제는 하나의 MySQL transaction이다.
- callback body에 graph를 추가하지 않는다.
- 실제 token 값을 문서·로그에 기록하지 않는다.

## Known Gaps

- AI endpoint service authentication 미구현(이슈 #181).
- `RESULT_WRITING` 장애 task의 reconciliation과 자동 복구가 없다.

## Update When

dispatch shape, 입력·결과 DTO, token/header, Redis stage, 결과 transaction, callback 또는 failure semantics가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.service.TimelineAiTaskFlowIntegrationTest'
```
