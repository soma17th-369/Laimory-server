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
- `TaskTokens`, `ProcessStage`, `TimelineDraftTask`, `TimelineTaskStore`
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
  "taskToken": "...",
  "dailyRecordId": 42,
  "window": {
    "startAt": "2026-07-22T00:00:00+09:00",
    "endAt": "2026-07-23T00:00:00+09:00"
  }
}
```

- `dailyRecordId`·`window`와 offset ISO-8601 포맷은 유지한다. source item은 싣지 않고 AI가 입력 조회
  API로 가져간다.
- 작업 전체를 인증하므로 토큰 필드명은 `taskToken`으로 통일한다. 256-bit 난수 bearer token이며 API
  서버는 Redis task에 SHA-256 hash만 저장한다.
- 접수 성공은 `202 Accepted` + `{"taskId":<동일>,"status":"PROCESSING"}`다.
- dispatcher는 4xx만 미접수 확정으로 분류한다. 비202·계약 불일치·타임아웃·5xx·전송 실패는 접수 불명이라
  PROCESSING을 유지하고 draft POST는 502 `-1009`로 끝난다.
- AI endpoint 자체 service authentication은 아직 없다(private network 전제).

### 2. 회전 Task Token과 Redis Process Stage

입력 조회·결과 저장·callback은 각 단계에서 받은 현재 token을 같은 header 이름으로 제시한다.

```http
Task-Token: <현재 taskToken>
```

- 매 요청은 task 조회 직후 제시 token의 hash와 Redis `tokenHash`를 constant-time 비교한다.
- `/s/api`에는 request principal이 없다. token 소유가 해당 task에 대한 capability다.
- 원문 token은 저장·로그하지 않는다.
- token 원문은 의미를 포함하지 않는 256-bit 난수다.
- 성공한 입력·결과 endpoint가 다음 token으로 교체하며, 호출 순서는 PROCESSING task의 내부
  `ProcessStage`가 제한한다.

```text
INPUT_PENDING
  → RESULT_PENDING
  → CALLBACK_PENDING
  → SUCCESS
```

- token hash+stage 교체와 terminal 전이는 현재 task JSON 전체를 기대값으로 비교하는 Redis Lua CAS다.
- 외부 polling 상태는 계속 `PROCESSING`/`SUCCESS`/`FAILED`만 노출한다.

### 3. 입력 조회 (AI → API)

```http
GET /s/api/{version}/timeline/drafts/{taskId}/input
Task-Token: <taskToken>
```

응답:

- `taskId`, `taskToken`(결과 저장용 다음 token), `recordDate`, `recordTimeZone`
- `window.startAt`/`endAt`
- `sourceItems[]`: `rawId`, `itemType`, nullable `startAt`/`endAt`, `payload`

`userId`·`dailyRecordId`·행 PK는 응답하지 않는다.

처리 규칙:

- token·PROCESSING·stage 검증을 개인 데이터 조회보다 먼저 한다.
- `INPUT_PENDING`에서만 허용한다.
- 응답 조립 뒤 새 token hash와 `RESULT_PENDING`을 한 CAS로 저장하고 새 token 원문을 응답한다.
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

- 성공 응답은 `{"taskToken":"..."}`이며 이 token을 callback에 사용한다.
- `RESULT_PENDING` 요청 하나만 새 token hash와 `CALLBACK_PENDING`을 CAS로 선점해 MySQL transaction을
  실행한다. 새 token 원문은 MySQL commit 뒤 응답할 때까지 AI에 노출하지 않는다.
- 이미 소비된 token 재요청은 token 불일치 401 `-1002`, 다른 stage 요청은 409 `-1017`이다.

검증:

- event와 event별 source가 각각 1건 이상
- `eventType`, non-blank `title`, `startAt`, `endAt >= startAt`, DB 문자열 길이
- 모든 `sourceRawId`가 이 task staging source에 존재
- 같은 record final graph에 채택 rawId가 아직 없음

MySQL transaction은 DB 검증, Event/Item/junction INSERT와 채택 source DELETE를 함께 commit한다.
offset 시각은 record timezone wall-clock으로 정규화하고 start 충돌은 +10분 nudge, end는 start 이상으로
clamp한다.

저장 예외가 호출부까지 돌아오면 가능한 경우 이전 RESULT token hash와 `RESULT_PENDING`으로 복구한다.

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
- result의 token/stage CAS 뒤 프로세스가 종료되면 새 token 원문이 AI에 전달되지 않아 task가
  PROCESSING TTL로 만료될 수 있다.
- MySQL commit 뒤 result 응답 유실 시 graph는 남지만 AI는 callback token을 얻지 못한다.
- 이 경로의 receipt, reconciliation, 자동 callback은 두지 않는 것이 수용된 MVP 한계다.
- task 만료 뒤 어떤 서버간 요청도 404 `-1001`이며 task를 부활시키지 않는다.
- PROCESSING TTL은 3분이고 token/stage 교체마다 다시 확보한다. terminal TTL은 24시간이다.

## Current Implementations

- `noop`: dispatch하지 않아 PROCESSING task가 TTL로 만료된다.
- `fake`: 응답마다 받은 다음 task token으로 자기 서버의 입력 → 결과 → callback endpoint를 실제 HTTP 호출한다.
- `http`: 실 AI 연동. 접수 timeout과 응답 계약을 검증한다.

## Invariants

- dispatch body 필드명은 AI 규격이 권위다.
- 서버간 단계마다 token을 교체하고 Redis에는 현재 token hash만 저장한다.
- 입력과 결과의 token hash+stage 전이는 Redis task JSON CAS다.
- 결과 graph와 채택 source 삭제는 하나의 MySQL transaction이다.
- callback body에 graph를 추가하지 않는다.
- 실제 token 값을 문서·로그에 기록하지 않는다.

## Known Gaps

- AI endpoint service authentication 미구현(이슈 #181).
- result token 교체·DB commit·응답 사이 장애의 reconciliation과 자동 복구가 없다.

## Update When

dispatch shape, 입력·결과 DTO, token/header, Redis stage, 결과 transaction, callback 또는 failure semantics가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.service.TimelineAiTaskFlowIntegrationTest'
```
