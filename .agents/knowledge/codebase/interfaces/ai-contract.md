# AI Dispatch, Input, Result and Callback Contract

## Scope

API 서버와 AI 사이의 HTTP 계약이다 — 타임라인 생성(dispatch → 입력 조회 → 결과 저장 → 상태 callback)과
User Memory 갱신(접수 → 결과) 두 흐름을 담는다. 둘은 서로 다른 작업이고 task·token을 공유하지 않는다.
AI는 MySQL·Redis에 직접 접근하지 않는다.

## Read When

AI dispatcher, 서버간 입력·결과 endpoint, task token, Redis task stage, 결과 저장 transaction 또는
callback body를 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineAiDispatcher` implementations와 dispatch DTO
- `TimelineAiTaskApi`, 입력·결과 DTO와 service
- `TimelineCallbackApi`, callback DTO와 service
- `TaskTokens`, `ProcessStage`, `TimelineDraftTask`, `TimelineTaskStore`
- `TimelineAiTaskFlowIntegrationTest`
- `UserMemoryUpdateDispatcher` implementations, `AiUserMemoryUpdateRequest`/`AiUserMemoryUpdateResultRequest`,
  `UserMemoryUpdateApi`, `UserMemoryUpdateResultService`, `UserMemoryUpdateWorker`,
  `TimelineSaveFlowIntegrationTest`

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
- `sourceItems[]`: `rawId`, `itemType`, 필수 `startAt`·nullable `endAt`, `payload` —
  `startAt` 필수는 draft 입력 경계(400)가 보장하며 AI 입력 계약(`CollectedSourceItem`)과 정렬된다.
  지오코딩이 허용 범위에서 부분 실패한 STAY/MOVEMENT payload는 `address` key가 생략되고
  `places: []`다(NON_NULL 직렬화 — 정상 "주소 없음"과 같은 wire shape, 실패 marker 필드는 없음).
  품질 guard(고유 좌표 실패 20% 이하·시간순 연속 실패 3개 미만)를 넘는 batch는 draft 생성 자체가
  502로 거절돼 이 API에 도달하지 않는다.

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
            "startAt":"...","endAt":null,"sourceRawIds":["..."],
            "question":"..."}]}
```

- 성공 응답은 `{"taskToken":"..."}`이며 이 token을 callback에 사용한다.
- `question`은 Event별 선택 필드다. 필드 누락·명시적 `null`·공백 문자열은 모두 저장 값 `null`(질문 없음)로
  수렴하므로 question 도입 이전 요청 shape가 그대로 통과한다. 서버 Jackson은 미지 필드를 무시하므로
  서버 배포 전에 AI가 먼저 `question`을 보내도 400이 아니라 무시된다.
- `RESULT_PENDING` 요청 하나만 새 token hash와 `CALLBACK_PENDING`을 CAS로 선점해 MySQL transaction을
  실행한다. 새 token 원문은 MySQL commit 뒤 응답할 때까지 AI에 노출하지 않는다.
- 이미 소비된 token 재요청은 token 불일치 401 `-1002`, 다른 stage 요청은 409 `-1017`이다.

검증:

- event와 event별 source가 각각 1건 이상
- `eventType`, non-blank `title`, `startAt`, `endAt >= startAt`, DB 문자열 길이
  (`title`·`subtitle`·`question` 각각 trim 후 255자 — 한 Event의 초과가 결과 batch 전체를 400으로 만든다)
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

### 6. User Memory 갱신 (별도 흐름 — draft와 무관)

타임라인 생성(1~5)과 **다른 작업**이다. 하루 기록 저장이 commit된 뒤 시작하고, endpoint는 접수와 결과
둘뿐이다 — 입력 조회가 없고(접수 body에 전부 싣는다), 폴링이 없고(저장은 이미 동기로 끝났다), 별도
callback도 없다(결과 호출이 종료 통보를 겸한다). 그래서 **token 재발급 지점이 없다**(작업당 token 하나).

```http
POST {base-url}/v1/user-memory
```

```json
{
  "taskId": "...", "taskToken": "...",
  "userMemory": null,
  "dailyTimelines": [{
    "recordDate": "2026-08-04", "recordTimeZone": "Asia/Seoul", "emotionType": null,
    "events": [{
      "eventType": "MEAL", "title": "...", "subtitle": "...", "question": "...",
      "startAt": "2026-08-04T12:10:00+09:00", "endAt": "2026-08-04T13:00:00+09:00",
      "memo": "..."
    }]
  }]
}
```

- AI 규격 초안은 `diaries[{date, content}]`로 "일기 본문"을 기대했지만 **우리 도메인에 일기 본문도,
  diary라는 개념도 없다** — `DailyRecord`에 텍스트 필드가 없고 하루의 내용은 `TimelineEvent` 목록이다.
  확정된 타임라인을 그대로 옮긴 `dailyTimelines[]`로 합의했다(2026-08-06).
- **이름·필드 구성은 형제 계약을 따른다**: 하루 식별은 입력 조회 응답과 같은 `recordDate`/`recordTimeZone`,
  Event는 공통 6개(`eventType`·`title`·`subtitle`·`question`·`startAt`·`endAt`)를 같은 순서로 두고
  흐름별 추가 필드 하나를 끝에 붙인다 — 결과 저장은 `sourceRawIds`, 여기는 `memo`다.
- `question`(우리 타임라인 AI가 쓴 문장)과 `memo`(그에 대한 사용자의 답)를 함께 보낸다 — 둘을 나누면
  `"응 좋았어"` 같은 memo가 맥락을 잃는다. `memo` 상한은 AI 규격에 맞춰 500자다.
- `items[]`(사진 등)와 행 PK(`timelineEventId`·`dailyRecordId`·`userId`)는 싣지 않는다(입력 조회 응답과
  같은 규칙 — 상관관계는 `taskId`).
- `dailyTimelines`는 **여러 건일 수 있다** — 접수는 하루 1회 배치가 전담하고, 한 사용자에게 밀린 날을
  묶어 보낸다(AI 상한 5건). 초과분은 다음 실행 몫이다.
- 접수 성공은 draft와 같은 `202 Accepted` + `{"taskId":<동일>,"status":"PROCESSING"}`다.
- `emotionType`은 입력 경로가 없어 현재 항상 null이지만 nullable 필드를 미리 뒀다.

```http
POST /s/api/{version}/user-memory/updates/{taskId}/result
Task-Token: <접수 body로 준 token>
```

```json
{"status": "SUCCESS", "userMemory": { "schemaVersion": "1.0", ... }}
{"status": "FAILED", "errorCode": 1210, "error": "..."}
```

- `userMemory`는 부분 병합이 아니라 **문서 전체**다. 서버는 파싱·정규화·검증 없이 그대로 저장한다 —
  스키마를 소유하고 검증하는 쪽이 AI 서버라, 받은 것을 왕복시키면 항상 유효하다(#253 opaque 계약).
- 적용 여부는 **base 문서 지문**(접수 때 보낸 문서의 SHA-256)이 판정한다. 불일치는 그 사이 다른 날짜의
  갱신이 문서를 교체했다는 뜻이라 409 `-1017`로 폐기한다 — 적용하면 그 날짜의 기여가 조용히 사라진다.
- FAILED는 DB 무변경 + 작업 종결이고 200이다. `errorCode`·자유 text `error`는 로그로만 남긴다.
- 성공·실패 어느 쪽이든 task를 지우므로 **중복·뒤늦은 결과는 404 `-1001`**이다.
- 이 경로는 `daily_records`를 건드리지 않는다 — 저장 API가 이미 SAVED로 commit했다.
- AI 재시도 정책(합의): timeout·5xx는 같은 body로 재시도, **4xx는 재시도 없이 중단**. 우리가 4xx를 내는
  경우는 전부 "재시도해도 달라지지 않음"이다. `400`도 계약 위반 시 나갈 수 있으므로 중단 대상이다.
- 반대 방향(우리 → AI)은 **같은 접수를 즉시 재시도하지 않는다** — AI를 두들기는 루프가 없고 circuit
  breaker도 두지 않는다. 재시도는 하루 1회 배치가 담당하며, 그 대상은 이 결과 호출이 "반영 못 함"으로
  표시한 날들이다.

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

User Memory 갱신도 같은 `app.ai.mode` 스위치로 세 구현을 고른다 — `noop`은 로그만, `fake`는 스키마
필수 필드만 채운 결정적 stub 문서로 자기 서버의 결과 endpoint를 실제 호출, `http`는 실 AI 연동이다.
`http` dispatch의 read timeout은 반드시 유한해야 한다 — 이 호출은 요청 스레드가 아니라 async 실행기
또는 재시도 배치 스레드에서 일어나므로 무한 대기는 다른 작업까지 정지시킨다.

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
