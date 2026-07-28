# AI Dispatch, Input, Result and Callback Contract

## Scope

API server와 AI 측 사이의 draft task 계약이다. dispatch(HTTP) → 입력 조회 → 결과 저장 → 상태 콜백
네 단계이며, 네 경계 모두 HTTP다. AI는 MySQL·Redis에 직접 접근하지 않는다.

## Read When

AI dispatcher, 서버간 입력·결과 endpoint, 단계별 토큰, 결과 저장 transaction 또는 callback body를 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineAiDispatcher` implementations (`NoOp`/`Fake`/`Http`) and `AiTimelineDispatchRequest`/`Response`
- `TimelineAiTaskApi`, `AiTimelineTaskInputResponse`, `AiTimelineResultRequest`/`Response`
- `TimelineAiTaskInputService`, `TimelineAiResultService`, `TimelineAiResultTransactionService`
- `TaskTokens`, `TimelineDraftTask`(단계별 hash), `TimelineAiResultReceipt`
- `TimelineCallbackApi`, `DraftTaskCallbackRequest`, `TimelineCallbackService`
- `HttpTimelineAiDispatcherTest`(dispatch fixture), `FakeTimelineAiDispatcherTest`(단계 순서 fixture),
  `TimelineAiTaskFlowIntegrationTest`

## Contract

### 1. Dispatch (API → AI)

```http
POST {base-url}/v1/timeline
Content-Type: application/json
```

```json
{"taskId": "...", "taskToken": "..."}
```

- 두 필드만 있으며 입력 데이터는 싣지 않는다 — AI가 `taskToken`으로 입력 조회 API를 호출해 받아간다.
  `dailyRecordId`·`window`는 계약에서 제외한다(AI는 DB 식별자를 알지 않는다).
- `taskToken`은 단계별 토큰 chain의 첫 토큰(T1) 원문으로 이 body로만 한 번 전달된다
  (로그·MySQL·Redis 저장 금지 — 서버는 hash만 보관).
- 접수 성공은 `202 Accepted` + body `{"taskId": <동일>, "status": "PROCESSING"}` — final 성공이 아니다.
  dispatcher는 202·동일 taskId·PROCESSING을 검증한다. 4xx 거절만 미접수 확정으로 task FAILED(`-1009`)
  종결을 시도하고, 비202·계약 불일치·타임아웃·5xx·전송 실패는 접수 불명(UNKNOWN)이라 PROCESSING을
  유지한다. 어느 실패든 draft POST는 502(`-1009`)로 끝나고 taskId를 반환하지 않는다.
- 현재 AI endpoint는 무인증이다(private network 전제) — production 전 service authentication 추가 시
  request header와 fixture를 양 저장소에서 함께 갱신한다.

### 2. 단계별 토큰 chain

토큰은 단계마다 다르며 이전 단계 토큰에서 **결정적으로 파생**한다.

```text
T1 = 256-bit 난수(dispatch body 전용)
T2 = HMAC-SHA256(key=T1, "timeline-task-token:v1:{taskId}:result")
T3 = HMAC-SHA256(key=T2, "timeline-task-token:v1:{taskId}:callback")
```

- task 생성 시 세 hash를 모두 계산해 Redis task JSON에 저장한다. 원문은 어느 단계도 저장하지 않으므로,
  다음 토큰 원문은 AI가 제시한 현재 토큰에서 그때 재계산해 응답에 싣는다.
- 검증은 단계별 저장 hash와의 constant-time 비교이며 불일치는 401 `-1002`다. 어느 단계 토큰이 틀렸는지는
  응답으로 구분해 주지 않는다(로그로만).
- 같은 단계를 몇 번 재시도해도 같은 다음 토큰이 나온다 — 응답 유실이 task를 고립시키지 않는다.
- one-time 소비 marker는 없다. 이전 단계 토큰의 재사용도 무해하므로(T1 재사용은 읽기 전용 재조회,
  T2 재사용은 영수증 히트 no-op) 단계 진행 시 이전 토큰을 무효화하지 않는다.
- **파생 chain은 호출 순서를 강제하지 않는다** — T1 보유자는 T2·T3를 계산할 수 있다. 토큰은 "dispatch를
  받은 AI임"을 인증할 뿐이고, 저장 없는 SUCCESS 콜백을 막는 권위는 DB 영수증이다(§5).

### 3. 입력 조회 (AI → API)

```http
GET /s/api/{version}/timeline/drafts/{taskId}/input
Task-Token: <T1>
```

응답은 JPA entity·DB 식별자를 노출하지 않는 전용 DTO다.

- `taskId`, `recordDate`, `recordTimeZone`
- `window.startAt`/`endAt` — record timezone 기준 offset ISO-8601(`yyyy-MM-dd'T'HH:mm:ssXXX`)
- `sourceItems[]` — `rawId`, `itemType`, offset `startAt`/`endAt`(시간 미상은 null), `payload`
  (staging JSON 그대로 통과 — 타입 권위는 payload 밖 `itemType`)
- `resultToken` — 다음 단계(T2)

처리 규칙:

- 토큰 검증과 PROCESSING 확인이 **개인 데이터 조회보다 먼저**다(`/s/api`엔 principal이 없어 토큰만이
  인증 수단이다). terminal task 조회는 409 `-1017`, 없음·만료는 404 `-1001`이다.
- 응답 조립에 성공하면 PROCESSING TTL을 다시 확보한다 — AI 추론이 이 응답 이후에 시작되기 때문이다.
  `processingStartedAt`은 보존하므로 폴링 `elapsedSeconds` 의미는 바뀌지 않는다.
- `userId`·`dailyRecordId`·table/entity 구조는 전달하지 않는다.

### 4. 결과 저장 (AI → API)

```http
POST /s/api/{version}/timeline/drafts/{taskId}/result
Task-Token: <T2>
```

```json
{"events": [{"eventType": "MEAL", "title": "...", "subtitle": null,
             "startAt": "...", "endAt": null, "sourceRawIds": ["..."]}]}
```

- 응답은 `{"callbackToken": "<T3>"}`다. 실패 보고는 이 endpoint가 아니라 콜백이 담당하므로 body에
  조건부 필드가 없다.
- confidence·추론 설명·질문·address/tag 등 현재 DB에 저장하지 않는 AI 내부 출력은 계약에 넣지 않는다.
- 이 endpoint는 graph만 저장하고 **task 상태를 전이하지 않는다**(전이는 콜백 책임).

검증(위반 시 아무것도 저장하지 않고 400 `-400`):

- event 1건 이상, event마다 `sourceRawIds` 1건 이상
- `eventType` allowlist(판별 불가는 `UNKNOWN` 명시), non-blank `title`, `startAt` 필수, `endAt >= startAt`
- DB column 길이(`title`/`subtitle` 255)
- 모든 `sourceRawId`가 이 task의 staging source에 존재
- 이 record의 기존 final Item(Event→junction 경유)에 같은 rawId가 없음

### 5. 결과 저장 transaction과 멱등성

서버가 다음 순서를 하나의 transaction으로 commit한다.

1. `timeline_ai_result_receipts`에 `task_id` PK로 영수증 INSERT + flush. duplicate key면 롤백 후
   "이미 반영"으로 200 응답한다(재시도·동시 중복 요청이 여기서 직렬화되므로 record lock을 두지 않는다).
2. DailyRecord 상태(DRAFT) 확인과 위 §4 DB 검증
3. offset 시각을 `record_timezone` wall-clock으로 정규화(MySQL `DATETIME`은 offset 미보존)
4. 기존 Event `start_at`과 정확히 겹치면 +10분씩 nudge, `end_at < start_at`이면 start로 clamp
5. distinct `rawId`마다 `timeline_items` 1행, 새 `timeline_events`, junction INSERT(append-only —
   기존 Event/Item/junction/memo는 수정·삭제하지 않는다)
6. 채택된 staging source만 DELETE(누락 source는 retention cleanup)

어느 단계에서 실패하든 영수증까지 함께 롤백되므로 부분 반영이 남지 않고, 올바른 결과의 재시도가 막히지
않는다. 영수증은 결과 내용을 저장하지 않는다 — 존재 자체가 "이미 반영"이다.

### 6. 상태 콜백 (AI → API)

```http
POST /s/api/{version}/timeline/drafts/{taskId}/callback
Callback-Token: <T3>   # FAILED는 T2도 허용
```

- body는 `status`, `errorCode`, `error`뿐이며 결과 graph는 없다(서버는 상태 전이만 기록).
- **SUCCESS는 T3만 허용**하고, 해당 task의 영수증이 있을 때만 받는다 — 없으면 409 `-1017`(저장 없는
  SUCCESS 차단). FAILED는 결과 저장 단계를 거치지 않아 T3를 받을 수 없으므로 T2도 허용한다.
- terminal task에 **같은 결과**가 다시 오면 그대로 200이고(재시도 안전), SUCCESS↔FAILED 상충은 409 `-1017`다.
- AI writer는 FAILED `errorCode`를 JSON integer(현재 `-1008`)로 보낸다. 문자열 코드는 허용하지 않으며,
  null·미지 integer 값은 `-1008`로 수렴한다. SUCCESS의 `errorCode`는 사용하지 않는다.
- 성공은 body 없는 HTTP 200이며 400/401/404/409는 `GlobalExceptionHandler` envelope를 사용한다.

## Failure Semantics

- AI가 FAILED를 알리면 허용된 public numeric code(현재 `-1008`)만 task state에 기록한다.
  자유 text `error`는 진단 log에만 남기고 polling 응답에는 저장·노출하지 않는다.
- 결과 저장 commit 후 콜백 전에 AI process가 종료되면 T3를 들고 있는 주체가 사라진다 — 원 task는
  PROCESSING TTL로 만료되고 저장된 graph는 남는다(수용된 MVP 한계 — 동일 source 전량 재시도는 `-1013`,
  일부 신규 source 재시도가 실질 복구 경로).
- 결과 저장은 성공했지만 응답이 유실된 경우는 재시도가 안전하다(영수증 멱등).
- task 만료 뒤 도착한 어떤 단계 요청도 404 `-1001`이며 task를 부활시키지 않는다.

## Current Implementations

- `noop`(기본): dispatch하지 않아 PROCESSING task가 TTL로 만료된다.
- `fake`(dev): 실 AI와 같은 순서로 자기 서버의 세 endpoint를 실제 HTTP로 호출한다. 추론이 없으므로
  분류는 `UNKNOWN` 고정이고 조회한 source 전부를 Event 하나로 묶는다. 재시도는 없다.
- `http`: 실 AI 연동 — `app.ai.http.base-url` 필수, 접수 타임아웃(connect 2s/read 5s 기본) 초과는
  접수 불명(UNKNOWN)으로 PROCESSING을 유지한 채 POST 502다. connect+read 합은 PROCESSING TTL(3m)의
  절반 미만이어야 기동한다.

## Invariants

- dispatch body 필드명은 AI 규격이 명명 권위인 공개 계약이다 — `HttpTimelineAiDispatcherTest`가 fixture로 고정한다.
- 서버간 응답의 시각은 record timezone offset ISO-8601이고, 저장은 항상 record timezone wall-clock이다.
- 결과 graph 저장과 채택 source 삭제, 영수증 기록은 하나의 transaction이다.
- 콜백 body에 결과 graph를 추가하지 않는다.
- SUCCESS 상태 전이는 영수증 확인 뒤에만 한다(토큰 소유가 저장을 증명하지 못한다).
- 실제 credential이나 단계별 토큰 값을 문서·log에 기록하지 않는다.

## Known Gaps

- AI endpoint service authentication 미구현(production 전 필수 — 이슈 #181).
- 결과 저장 후 콜백 유실 task의 자동 복구 경로가 없다(수용된 MVP 한계).

## Update When

dispatch shape/endpoint, 입력·결과 DTO, 단계별 토큰 규칙, 결과 저장 transaction 계약, callback header/body,
인증 또는 failure semantics가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.HttpTimelineAiDispatcherTest' \
  --tests 'com.laimory.server.timeline.service.AiDispatcherWiringTest' \
  --tests 'com.laimory.server.timeline.service.Fake*' \
  --tests 'com.laimory.server.timeline.service.TimelineAi*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.service.TimelineAiTaskFlowIntegrationTest'
```
