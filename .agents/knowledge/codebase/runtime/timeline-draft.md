# Timeline Draft Runtime

## Scope

timeline draft 생성 요청부터 AI dispatch, 서버간 입력 조회·결과 저장, callback, polling, Event 조회·편집과
cleanup까지의 runtime sequence다.

## Read When

draft POST·polling·서버간 입력/결과·callback·append·Event 조회·편집·삭제·Redis state·staging cleanup을 바꿀 때 읽는다.

## Authoritative Sources

- `TimelineDraftTaskService`, `TimelineDraftPreparationService`, `TimelineDraftTaskPollingService`
- `TimelineAiDispatcher`(+`AiTimelineDispatchRequest`), `TimelineAiTaskInputService`,
  `TimelineAiResultService`/`TimelineAiResultTransactionService`, `TimelineCallbackService`, `TimelineTaskService`
- `TimelineEventEditService`와 Event 편집 transaction service
- `TimelineSaveService`/`TimelineSaveTransactionService`, `UserMemoryUpdateWorker`,
  `UserMemoryUpdateResultService`, `UserMemoryUpdatePendingStore`/`UserMemoryUpdateTaskStore`
- `DailyTimelineService`(읽기), `TimelineDeletionService`/`TimelineDeletionTransactionService`
- timeline entities(junction 포함), repositories, Redis stores and integration tests
- `src/main/resources/db/schema.sql`, `application*.properties`

## Current Implementation

### Create

1. `POST /a/api/{version}/timeline/drafts`가 요청을 받는다(유효 Bearer 필수 — 401은 security 단계 처리).
2. 인증 principal userId 하나가 record 조회·enrich photo key·staging row·Redis task owner에
   동일하게 흐른다. task는 owner를 세 상태 모두 보존한다.
3. 요청의 `recordDate`(클라 선택 날짜)와 `timelineWindow`(필수, `startTime < endTime`)를 side effect 전에
   검증한다 — 서버는 recordDate를 파생하지 않고 window를 계산·보정하지 않는다(pass-through). source item도
   같은 경계에서 전 타입 공통 `startAt` 필수로 검증한다(누락 → 400 `-400`, `endAt`은 nullable).
4. UUIDv7 `taskId`와 최초 입력 조회용 256-bit `taskToken`을 만들고(token 원문은 dispatch, SHA-256 hash는
   Redis용), SAVED record를 거부하며 기존 final `rawId`(record의
   Event→junction→Item 경로 조회)와 request 안
   중복을 제외한다. 제외 결과 신규 item이 0이면 409 `-1013`.
5. geo/photo enrich를 DB transaction 밖에서 수행한다. 필터 뒤 지오코딩 대상 unique coordinate가 30개
   (`app.geo.max-unique-coordinates`)를 넘으면 외부 호출 전에 400 `-400`으로 거절한다. 지오코딩은 좌표별
   최종 outcome을 materialize해 품질 판정한다 — unique 실패 20% 초과(`5F > U`) 또는 시간순(observation
   `startAt`/MOVEMENT END는 `endAt`-or-`startAt`, `rawId`·START<END tie-break) 연속 실패 3개면 저장 전
   502(영구 실패 포함 `-1015`, 아니면 `-1014`)로 거절하고, 허용되면 실패 좌표만 `address` 생략·
   `places=[]`로 계속한다. 단 **`LOCAL_REJECTED`(자기 pool 혼잡)는 upstream 품질 신호가 아니므로
   두 규칙 어디에도 계수하지 않고**(D2에서는 무정보 skip — reset 아님) 해당 좌표만 fallback으로
   강등한다(#262). local 혼잡만으로는 502가 나지 않는다.
6. **DailyRecord 선생성 + source 저장을 한 트랜잭션으로 커밋한다**(`TimelineDraftPreparationService`):
   `(userId, recordDate)` find-or-create, 기존 DRAFT면 `recordAt/recordTimezone`을 이번 요청 값으로 즉시
   갱신, SAVED 재확인(throw → 전체 롤백), source rows 저장. 반환된 `dailyRecordId`가 task·dispatch에 실린다.
7. Redis task를 `PROCESSING`/`INPUT_PENDING`으로 저장한다(`dailyRecordId`·owner·local window·token
   hash·`processingStartedAt` — record 메타데이터는 저장하지 않는다). 저장 실패하면 이번 task의 source rows만
   보상 삭제하고 DailyRecord는 유지한다(이번 task가 처음 만든 record인지 durable하게 알 수 없고 empty
   DRAFT 재사용이 안전 — 실패 task의 empty DRAFT는 같은 날짜 재시도가 재사용하며 자동 cleanup하지 않는다).
   같은 저장 Lua가 관측 전용 전역 PROCESSING index와 사용자별 진행 작업 index
   (`timeline:draft-task:user:{userId}:processing`)에 시작 시각 score로 taskId를 추가하고 사용자 index
   key TTL을 PROCESSING TTL로 갱신하며, terminal 저장 Lua가 두 index에서 함께 제거한다. 전역 index는
   90초 초과 stuck gauge에만, 사용자 index는 진행 작업 목록 조회의 후보에만 쓰며 task 상태·소유권·
   callback 계약의 권위는 기존 JSON이다.
8. AI dispatcher를 호출한다 — body는 `taskId`·`taskToken`·`dailyRecordId`·offset `window`이며,
   기존 데이터 필드와 window 포맷을 유지한다. source item은 싣지 않는다
   (계약 상세는 [ai-contract](../interfaces/ai-contract.md)). 접수(202) 확인까지 동기이며,
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

`app.ai.mode=noop`은 아무 요청도 만들지 않아 task가 만료된다.
`fake`는 실 AI와 같은 순서로 자기 서버의 입력·결과·콜백 endpoint를 실제 HTTP로 호출하며 retry하지 않는다.
`http`는 실 AI 연동이다.

### AI input, result and callback

1. **입력 조회**(`GET /s/api/{v}/timeline/drafts/{taskId}/input`, `Task-Token`): task/token/PROCESSING/
   `INPUT_PENDING` 확인 **다음에** record·staging을 읽어 정규 입력 DTO를 만든다. 성공하면 새 token hash와
   `RESULT_PENDING`을 CAS 저장하고 새 token 원문을 응답 body의 `taskToken`으로 반환한다.
2. **결과 저장**(`POST .../result`, 입력 응답의 `Task-Token`): 새 callback token hash와
   `CALLBACK_PENDING`을 CAS로 선점한 요청만 DB 검증·시각 정규화·+10분 nudge/clamp·Event/Item/junction
   INSERT·채택 source DELETE를 하나의 MySQL transaction으로 commit한다. 저장 예외면 가능한 경우 이전
   result token hash와 RESULT_PENDING으로 복구한다. 성공하면 callback token 원문을 응답 body에 반환한다.
3. **콜백**(`POST .../callback`, 결과 응답의 `Task-Token`): SUCCESS는 CALLBACK_PENDING, FAILED는 결과
   저장 전 stage에서만 허용한다. terminal CAS에 처음 성공한 요청만 완료 푸시를 예약하고 같은 terminal
   재전송은 200, 상충은 409 `-1017`이다.
4. terminal 저장 실패는 전파된다. terminal로 저장된 현재 token으로 같은 콜백을 다시 보낼 수 있다.

**수용된 MVP 한계**: Redis와 MySQL은 분산 transaction이 아니다. token 교체 뒤 응답 유실 또는 프로세스
종료 시 AI가 다음 token을 얻지 못해 task가 PROCESSING TTL로 만료될 수 있다. MySQL commit 뒤 result 응답
유실이면 graph도 남는다. receipt, reconciliation, 자동 callback은 두지 않는다.

### Polling and read

- `GET /a/api/{version}/timeline/daily-records`는 principal userId의 DRAFT/SAVED DailyRecord 전체를
  최신 날짜·ID 내림차순으로 반환한다(빈 record 포함, 없으면 200 `timelines=[]`).
  외부 하루 단건의 날짜 기반 공개 경로는
  `GET /a/api/{version}/timeline/daily-records/{recordDate}`이며 `(userId, recordDate)`가 일치하는
  한 건만 반환한다. 기존 `GET .../daily-records/by-id/{dailyRecordId}`는 같은 응답을 반환하는 deprecated 호환
  경로다. 없음·비소유는 두 경로 모두 404 `-404`로 은닉하며, record→Event→junction→Item을 한 read-only
  transaction에서 읽어 Event별 `items`까지 조립한다.
- `GET /a/api/{version}/timeline/events/{timelineEventId}`는 Event의 부모 record를 통해 principal 소유권을
  확인하고 DRAFT/SAVED Event와 연결 Item을 반환한다. Event·부모 record 없음과 부모 비소유는 Event 404로
  은닉하며 Item이 없으면 `items=[]`다.
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
- 하루 타임라인 및 Event 단건 조립(`DailyTimelineService`)은 읽기 전용이며 사용자 전체도 record별 단건
  반복 없이 record/Event/junction/Item 4단계 bulk SELECT로 읽는다. Event별 Item을 junction으로 로드해
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
- 삭제된 PHOTO 재추가는 새 upload identity다. Android는 같은 로컬 사진을 다시 선택해도 새 presign
  응답의 filename을 PATCH에 사용하고 과거 filename을 재사용하지 않는다. 이미 업로드를 마친 **동일
  pending addition**의 PATCH 재시도만 그 pending filename을 보존할 수 있으며, 서버는 pending delete
  key 재사용을 별도로 조회·차단하지 않는다.

### Delete

- Event 삭제: preflight 뒤 DB transaction에서 owner/DRAFT 재확인 → 삭제 Event에만 연결된 orphan Item
  판정 → orphan PHOTO delete-job insert와 원문 PHOTO Item 보존 → Event 삭제(junction은 FK cascade) +
  non-PHOTO orphan 명시 삭제. 날짜 Redis guard는 취득하지 않는다.
- DailyRecord 삭제의 날짜 기반 공개 경로는 `(principal userId, recordDate)`로 record를 찾는
  `DELETE .../daily-records/{recordDate}`다. 조회한 `dailyRecordId`를 snapshot한 뒤 기존 ID 기반
  삭제 transaction을 호출하며 transaction이 그 정확한 ID의 owner/DRAFT를 재확인한다. lookup 뒤 같은
  날짜 record가 재생성돼도 새 record로 대상을 바꾸지 않는다. 기존 `DELETE .../daily-records/by-id/{dailyRecordId}`는
  같은 transaction을 호출하는 deprecated 호환 경로다.
- DailyRecord 삭제 transaction은 record의 Event 집합에만 연결된 orphan Item을 계산해 PHOTO job insert·원문
  PHOTO Item 보존과 Record/Event/junction/non-PHOTO Item hard delete를 같은 commit으로 묶는다. record 밖
  Event에 연결된 후보는 방어적으로 shared 취급해 유지한다.
- Event와 DailyRecord DELETE는 MySQL commit 뒤 S3 완료를 기다리지 않고 200을 반환한다. 현재 REST
  프로세스의 환경당 단일 worker는 checked-in default인 매일 03:00 `Asia/Seoul`(cron/zone 환경 override
  가능)에 oldest job 최대 1,000개를 verbose `DeleteObjects`로 한 번 처리하고 `Deleted` job과 원문 PHOTO
  Item만 한 transaction에서 최종 삭제한다. Error·응답 누락·SDK 예외와 1,000개 초과분은 기본 cadence상
  다음 날 실행에서 재시도·처리한다. 실행 시각에 애플리케이션이 내려가 있어도 catch-up하지 않으며 job은
  다음 실행까지 MySQL에 남는다.

### Save (DRAFT→SAVED)와 User Memory 갱신

- `POST /a/api/{version}/timeline/daily-records/{recordDate}/save`(body 없음). `(principal userId,
  recordDate)`로 record를 찾아 없음·비소유는 404(`-404`), SAVED는 409(`-1003`)로 <b>부수효과 전에</b>
  거절하고, 별도 transaction service가 조건부 UPDATE(`WHERE status='DRAFT'`)로 전이한다. 영향 행 수 0은
  재조회로 404/409를 분류한다 — 이 UPDATE가 저장 흐름의 유일한 직렬화 지점이다.
- **전이와 User Memory 교체는 서로 다른 API가 담당하는 서로 다른 transaction이다.** 저장 API는 전이만
  commit하고 200을 반환하며, 교체는 10초+ 뒤 AI가 결과를 들고 왔을 때
  `POST /s/api/{version}/user-memory/updates/{taskId}/result`가 수행한다. 그래서 사용자의 저장이 AI의
  성패에 묶이지 않는다.
- 이 분리가 두 가지를 없앤다: **폴링 불필요**(저장 응답이 곧 완료), **AI 처리 중 편집 창 없음**(요청
  시점에 이미 SAVED라 모든 편집이 기존 불변식으로 거절된다).
- 요청 스레드는 AI를 호출하지 않는다. 저장 commit 뒤 그 하루를 갱신 대기 큐
  (`timeline:user-memory-update:pending` sorted set, member `userId:dailyRecordId`, score 최초 기록
  시각)에 넣기만 하고 곧바로 응답한다. Redis 쓰기 한 번이라 async로 넘기지 않는다 — 실행기 포화 시
  그 하루가 유실될 뿐이다. 등록 실패는 로그만 남기고 200을 깨지 않는다.
- **접수는 하루 1회 배치**(`dispatchPendingUpdates`, 기본 04:30 cron) **한 곳에서만 한다.** 저장된
  하루는 예외 없이 큐를 거치고, **반영이 확인될 때만** 큐에서 빠진다(outbox).
- **왜 저장 시점에 보내지 않는가**: AI 계약이 "202 접수 → 백그라운드 처리 → 완료 시 결과 API 호출"이라
  접수 성공이 반영 성공이 아니다. 접수한 날을 큐에 넣지 않으면, AI가 202를 준 뒤 결과를 주지 않을 때
  task는 TTL 3분에 사라지고 guard도 풀리고 **재시도할 근거가 아무 데도 남지 않는다.** 대가는 반영
  지연이 최대 cron 주기(기본 24시간)라는 것인데, User Memory는 다음 타임라인 품질을 높이는 보조
  데이터라 즉시성이 요구되지 않는다.
- 사용자 guard는 `timeline:user-memory-update:user:{userId}`(SET NX, TTL 3분)이고, **획득 실패가 곧
  "이 사용자의 갱신이 진행 중"이라는 판정**이다. 그래서 별도의 진행 상태 저장 없이 guard 하나가
  직렬화와 실패 판정을 겸한다. 배치는 사용자당 한 번만 보내므로 한 실행 안에서는 경합이 없고, guard가
  막는 것은 앞선 실행의 접수가 아직 진행 중인 경우다.
- 여러 인스턴스가 동시에 드레인해도 안전하다 — guard를 잡은 하나만 진행하고 못 잡은 쪽은 큐를 건드리지
  않으므로 항목이 되살아나지 않는다.
- **배치는 한 사용자의 밀린 날들을 한 요청으로 묶는다**(AI 계약 상한 5건). guard가 사용자당 하나라
  나눠 보내면 N일이 밀렸을 때 N일이 걸린다. 초과분은 큐에 남아 다음 실행이 가져간다.
- **배치도 결과를 기다리지 않는다.** 응답을 기다리는 척하려면 폴링을 얹어야 하고 그건 프로토콜과 싸우는
  짓이다.
- **반영 확인과 큐 정리는 결과 endpoint가 한다** — 성패를 아는 유일한 지점이다. 반영되면 그 날들을 큐에서
  빼고, 반영하지 못하면(FAILED·지문 불일치·계약 위반) 큐에 남아 다음 배치가 다시 시도한다.
- 배치는 접수만 하고 큐를 비우지 않는다. **걷어내는 경우는 하루 기록이 삭제돼 갱신할 재료가 사라졌을 때
  하나뿐이고**, 그 판정은 재료를 실제로 읽는 `dispatch`가 한다 — 일부만 사라진 경우도 같은 자리에서
  걷어낸다. 접수 body에서 빠지는 순간 결과 endpoint가 지울 근거를 잃기 때문이다. 4xx 거절은 남긴다
  (계약 불일치처럼 우리 배포로 풀리는 4xx가 있어, 지우면 고친 뒤에도 그 날은 복구되지 않는다).
- 큐 항목은 **최초 기록 시각을 유지한다**(ZADD NX). 재시도로 다시 기록돼도 시한이 연장되지 않아야
  영영 안 되는 날이 `retention`(기본 30일)에 걸려 정리된다. 만료분 청소는 읽기와 쓰기 양쪽에 있어,
  배치가 멈춘 사이에도 key가 무한히 자라지 않는다.
- **접수 body와 base memory 지문은 guard를 잡은 뒤 만든다.** 밀려 있는 동안 앞선 날짜의 갱신이 문서를
  바꾸므로, 미리 조립해 두면 낡은 문서를 base로 삼게 되고 미루는 것 자체가 무의미해진다.
- 접수 body는 확정된 타임라인을 구조화한 `dailyTimelines[].events[]`다(`question`·`memo` 포함, `items[]`와
  행 PK 제외, 시각은 record timezone offset). 접수 자체를 다시 시도하지는 않는다 — AI를 두들기는 루프가
  없고 circuit breaker도 두지 않는다. 재시도는 다음날 배치가 담당한다.
- 결과 적용은 token 검증 → base 지문 대조 → 문서 전체 교체 순이다. 지문이 다르면 그 사이 다른 날짜의
  갱신이 문서를 교체했다는 뜻이라 409(`-1017`)로 폐기한다. 성공·실패 어느 쪽이든 task와 guard를 지우므로
  중복·뒤늦은 결과는 404(`-1001`)가 된다. 이 경로는 `daily_records`를 건드리지 않는다.

### Retention and cleanup

- PROCESSING TTL: 3분(입력 조회·stage 전이마다 재확보) / SUCCESS·FAILED TTL: 24시간 /
  source staging retention: 7일
- PROCESSING 만료는 Redis key 소멸이지 FAILED 전이가 아니다 — scheduler 복구 없이 이후 폴링·콜백이
  404(`-1001`)로 수렴한다. 만료 전에 task 조회를 통과한 callback은 기존 terminal 전이를 완료할 수 있다.
- PROCESSING 관측 index는 terminal 전이 때 제거하고 gauge read가 3분(PROCESSING TTL)보다 오래된 고아
  member를 정리한다.
- 사용자별 진행 작업 index key는 PROCESSING 저장마다 TTL이 3분으로 갱신되고, 마지막 생성 뒤 3분
  inactivity면 통째로 만료한다(key TTL은 member별 TTL이 아님). member 회수는 terminal ZREM과 목록 조회
  lazy prune이 담당하며 별도 sweep은 없다 — 3분 미만 간격 생성이 terminal·조회 없이 계속되면 만료
  member가 누적될 수 있다(수용된 MVP trade-off).
- cleanup 대상은 만료된 source 행(omitted·FAILED task 잔여)이다. 채택된 source는 결과 저장 transaction에서
  이미 삭제돼 final Item이 참조하는 S3 객체를 지울 일이 없다.
- 만료된 PHOTO source는 S3 object 삭제가 성공한 뒤 row를 삭제한다. 실패하면 row를 남겨 재시도한다.

## Invariants

- AI dispatch는 application DB transaction 안에서 기다리지 않는다(선생성 commit 후 dispatch).
- 저장 전이와 User Memory 교체는 하나의 transaction이 아니다 — 저장 API가 전이를, 결과 API가 교체를
  각각 commit한다. User Memory는 저장 성패와 무관한 보조 데이터다.
- User Memory 갱신 접수 body와 base 지문은 사용자 guard를 잡은 뒤에 만든다.
- Redis SUCCESS는 CALLBACK_PENDING callback에서만 전이한다.
- draft 결과 graph는 서버가 소유한다(결과 저장 transaction). Event PATCH의 수동 PHOTO Item/junction도
  서버 transaction이다. AI는 어떤 테이블도 직접 쓰지 않는다.
- 단계마다 회전하는 task token은 매 요청 hash 비교로 검증하고 Redis `ProcessStage`/CAS가 호출 순서와
  동시 writer를 제한한다.
- 완료 푸시는 결과 전달 경로가 아니다 — polling이 권위 원천·유실 안전망이다(durable retry/outbox 없음).

## Known Gaps

- 결과 저장 후 callback 유실 task의 자동 복구 경로가 없다(수용된 MVP 한계 — ai-contract 참고).
- emotion 설정 API가 없다.
- User Memory 갱신이 **끝내 안 된 날**(7일 retention 안에 반영 못 함)은 그 날의 내용이 memory에 영영
  반영되지 않는다. 저장은 됐으니 사용자가 다시 저장할 일도 없다. guard 충돌로 인한 누락은 대기 재시도가
  없앴고, 남은 이 구멍은 재시도·순서 보장을 가진 MQ 도입과 함께 다룬다. 그전까지는 포기·FAILED를
  `userId`/`dailyRecordId`/`taskId` 로그로만 관측한다.
- 대기 중인 두 날짜가 경합하면 memory에 병합되는 순서가 정해지지 않는다. 누락이 아니라 순서 문제라
  수용한다.
- presign 뒤 draft가 만들어지지 않은 orphan S3 object는 cleanup하지 않는다.
- 실패 task가 남긴 empty DRAFT의 자동 cleanup은 없다(같은 날짜 재시도가 재사용).
- 같은 날짜 draft·수동 PHOTO 추가·삭제가 겹칠 때의 graph 정합성 보장은 미구현이다. 현재는 공통
  admission guard 없이 각 작업의 transaction·preflight만 유지한다.

## Update When

단계 순서, compensation, dispatch/입력/결과/callback 계약, junction 조회·삭제 규칙, TTL, append 또는
cleanup이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.timeline.service.*'
docker compose up -d
./gradlew integrationTest --tests 'com.laimory.server.timeline.*'
```
