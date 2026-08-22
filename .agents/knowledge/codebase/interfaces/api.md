# HTTP API Contract

## Scope

HTTP path, versioning, response envelope, error, authentication 표시와 transaction ID 계약을 설명한다.

## Read When

endpoint, DTO, HTTP status, error code/message, OpenAPI annotation 또는 transaction ID 노출을 바꿀 때 읽는다.

## Authoritative Sources

- `com.laimory.server.common.ApiUrls`
- 각 feature의 `*Api.java`, controller, request/response DTO
- `ApiResponse`, `ExceptionType`, `GlobalExceptionHandler`
- `OpenApiConfig`, API/controller/error contract tests
- 실행 중 생성되는 OpenAPI 문서

수동 endpoint·field 목록보다 위 source가 우선한다.

## Current Implementation

### Path spaces

| Prefix | Contract | Current enforcement |
|---|---|---|
| `/api/{version}` | 인증 없는 app-facing public API | public |
| `/s/api/{version}` | server-to-server API, endpoint별 자체 인증 | 단계별 task token을 endpoint가 강제 |
| `/a/api/{version}` | bearer-authenticated user API | security chain이 `authenticated()` 강제 — 무토큰/무효 토큰 401 `-2001` |

`version`은 `ApiUrls.VERSION` 정규식 path variable을 사용한다. controller는 값을 service로 전달하고
version별 동작은 service가 결정한다.

보호 operation 25개(timeline 16 + push-registrations PUT/DELETE + push-settings GET·PUT 2종 +
users GET /me·DELETE /me + terms agreements GET/POST)는 `bearerAuth` security requirement와
401 응답을 문서화한다. principal parameter는 operation마다 정확히 하나다 —
콘텐츠·push operation은 hidden `@CurrentSubject UUID subjectId`, 회원 account operation은 hidden
`@AuthenticationPrincipal Long userId`로 주입돼 둘 다 OpenAPI parameter에 나타나지 않는다(클라이언트
입력이 아님). 인증 흐름 상세는 [authentication runtime](../runtime/authentication.md)이 소유한다.

`POST /a/api/{version}/timeline/drafts`의 각 sourceItem은 `startAt` 필수·`endAt` nullable이다(원래
timestamp 계약 — 누락 `startAt`은 lookup·저장·dispatch 전 400/`-400`). `rawId`는 canonical lowercase
UUID(version 무관)만 허용하며 위반은 400/`-400`이다(허용값은 정규화 없이 저장/echo). 지오코딩 대상 고유 좌표
(rawId dedupe·기존 저장 item 제외 뒤 STAY 1개·MOVEMENT 최대 2개 좌표를 dedupe한 수)는 최대 30개이며
초과는 외부 지도 API 호출 없이 400/`-400`이다 — `sourceItems` 배열 길이 제약(`maxItems`)이 아니라
runtime 파생 계산이라 OpenAPI에는 description과 400 응답으로만 표현한다. 지오코딩 부분 실패 허용/거절
경계는 [timeline draft runtime](../runtime/timeline-draft.md)이 소유한다.

`GET /a/api/{version}/timeline/drafts`는 인증 사용자가 소유한 현재 진행 중(`PROCESSING`) draft 작업의
taskId만 생성 최신순으로 `body.taskIds` 배열에 반환한다. 진행 작업이 없으면 404가 아니라 200과
`taskIds=[]`다. 완료·실패·만료 작업과 타 사용자 작업은 개별 오류 없이 목록에서 제외한다(존재 비노출).
목록은 앱 재진입 시 잃은 taskId를 재발견하는 힌트이고, 각 작업의 최신 상태·결과는 기존 단건 폴링
(`GET .../drafts/{taskId}`)이 권위다. Redis 권위 read·task JSON 해석 실패는 catch-all 500 `-500`이다.

`GET /a/api/{version}/timeline/daily-records`는 인증 사용자의 DRAFT/SAVED DailyRecord 전체를
`recordDate DESC, dailyRecordId DESC` 순서로 반환한다. 기록이 없으면 200과 `timelines=[]`다.
하루 단건의 날짜 기반 공개 계약은
`GET /a/api/{version}/timeline/daily-records/{recordDate}`이며 `(request subjectId, recordDate)`가
일치하는 DRAFT/SAVED record를 반환한다. 기존
`GET /a/api/{version}/timeline/daily-records/by-id/{dailyRecordId}`도 같은 응답·소유권 계약으로 동작하지만
Android 전환 동안만 유지하는 deprecated 호환 API다. 없음·비소유는 두 경로 모두 같은 404 `-404`로
은닉하며 `DailyTimelineResponse.dailyRecordId`는 응답에 계속 포함한다. `DailyTimelineResponse`는
non-null `status`(`DRAFT`/`SAVED`)를 포함한다 — 전체·날짜·deprecated ID 단건 조회와 draft polling
SUCCESS `body.result`가 같은 공용 DTO라 네 경로 모두 상태가 일관되게 나간다(상태별 필터는 없다).
필드 순서는 `dailyRecordId`·`recordDate`·`status`·`emotionType`·`events`다. 전체·단건 응답 모두
Event별 연결 Item을 `events[].items[]`에 포함한다.

`GET /a/api/{version}/timeline/monthly-records?year=&month=`는 인증 사용자가 소유한 해당 월(양끝 포함)의
DRAFT/SAVED DailyRecord를 `recordDate` 오름차순으로 반환하는 캘린더용 경량 조회다. 각 항목은
`MonthlyDailyRecordResponse(recordDate, nullable emotionType)`뿐이고 `dailyRecordId`·`status`·`events`는
싣지 않으며 Event·Item graph를 조회하지 않는다. null 감정은 key 생략이 아니라 명시적 JSON null이다.
기록 없는 월은 404가 아니라 200과 `dailyRecords=[]`다. `year`·`month`는 필수 정수 query parameter이며
`year`는 1000~9999(MySQL `DATE` 지원 범위), `month`는 1~12만 허용한다 — 누락·비정수·범위 밖은 모두
400 `-400`이다. URL·DTO·method 이름에 `calendar`는 쓰지 않는다(Source Item `ItemType.CALENDAR`와 충돌).

`GET /a/api/{version}/timeline/events/{timelineEventId}`는 인증 사용자가 소유한 DRAFT/SAVED record의
Event 하나와 연결 Item을 기존 `TimelineEventResponse`로 반환한다. Event·부모 record 없음과 부모 비소유는
같은 404 `-404`로 은닉하고, Item이 없으면 `items=[]`다.

`TimelineEventResponse`는 일별 목록·단건 조회 모두 nullable `question`(AI가 Event마다 만든 질문)과
nullable `place`·`address`(AI가 Event 단위로 고른 장소명과 그 주소)를 포함한다. 값이 없으면 `null`이며,
사용자 편집 API 입력 계약에는 없어 앱이 바꿀 수 없는 읽기 전용 필드다. draft polling SUCCESS의 `result`도
같은 DTO라 세 필드가 그대로 실린다.

`PATCH /a/api/{version}/timeline/events/{timelineEventId}`는 기존 Event 상세 편집 endpoint 하나에서
`title`·`subtitle`·`startAt`·`endAt`(네 key 모두 필수), 선택적 `eventType`, 선택적 `memo`와 선택적
`photosToAdd`를 처리한다. `memo` 부재는 변경 없음이고 null·blank는 제거다. `photosToAdd` 부재 또는 빈
배열은 Item 변경 없음이며 명시적 null은 400이다. 배열 원소는
`rawId`·`startAt`·`endAt`과 PHOTO payload(`filename`, `clientPhotoUri`, `latitude`, `longitude`)만 받는다 —
`description`과 `photoUrl`은 입력 계약에 없다. `rawId`는 draft source와 같은 canonical lowercase UUID
규칙이며 위반은 400이다. non-empty 추가는 Event/memo 변경과 PHOTO Item/junction 저장을
한 DB transaction으로 commit한다. 성공 응답은
`200 + ApiResponse<Void>`이고 `body=null`이다. 신규 PHOTO의 서버 ID가 필요하면 날짜 기반 DailyRecord 단건 GET으로
권위 상태를 다시 조회한다. 별도 PHOTO 추가 endpoint는 없고
`PUT .../events/{timelineEventId}/memo`도 memo만 교체하는 현재 지원 API이며 성공 응답은 동일하게
`body=null`이다. `photosToAdd`의 full object key에 `PENDING` PHOTO delete job이 있으면 job을 취소하고
보존 Item을 재연결하며, 유효한 `PROCESSING`이면 같은 object key 생성을 막고 409 `-1019`를 반환한다.
기존 operation을 확장한 것이라 이 편집 계약으로 보호 operation 수가 늘지는 않았다.

`DELETE /a/api/{version}/timeline/events/{timelineEventId}`와 날짜 기반
`DELETE /a/api/{version}/timeline/daily-records/{recordDate}`는 필요한 PHOTO S3 삭제 작업·원문
PHOTO Item 보존과 기존 root/junction/non-PHOTO hard delete가 MySQL에서 commit되면 200을 반환한다. 기존
`DELETE /a/api/{version}/timeline/daily-records/by-id/{dailyRecordId}`도 같은 의미로 동작하는 deprecated 호환
API다. S3 완료는 비동기 worker 책임이며, 성공 뒤 원문 PHOTO Item과 job을 최종 hard delete하므로 S3 장애를
동기 502로 반환하지 않는다. 없음·비소유 404 계약은 유지하고 SAVED record도 삭제할 수 있다. 잘못된 날짜 형식은 400이며,
같은 날짜 작업 중이라는 이유로 `-1016`을 반환하지 않는다.

`DELETE /a/api/{version}/timeline/events/{timelineEventId}/items/{timelineItemId}`는 Event-Item junction
한 줄만 해제한다 — 다른 Event에 연결된 같은 Item은 유지되고, 마지막 Event 참조가 사라진 PHOTO는 위
삭제 API들과 같은 PHOTO 삭제 작업 규칙(보존 + worker 최종 삭제)을 따른다. 현재 정책상 PHOTO Item만
허용하며 연결된 non-PHOTO는 400 `-1018`이다. Event 없음·비소유·Item 없음·해당 Event 미연결은 구분 없이
404 은닉이고(미연결 non-PHOTO도 404가 우선), SAVED record에서도 해제할 수 있다. 성공 응답은 `body=null`이다.

`POST /a/api/{version}/timeline/daily-records/{recordDate}/save`는 필수 request body
`{"emotionType": "..."}`(허용값 `VERY_HAPPY`·`HAPPY`·`NEUTRAL`·`UNHAPPY`·`VERY_UNHAPPY`)로 하루 감정을
받아 그 날짜의 DRAFT record를 SAVED로 확정한다. 감정과 `status=SAVED`는 같은 조건부 UPDATE 하나로
함께 commit된다(부분 상태 없음). **200이 곧 저장 완료다** — 전이가 commit된 뒤 응답하므로 클라이언트가
기다릴 비동기 작업이 없고 폴링 endpoint도 없다. 저장 후에도 Event PATCH·memo PUT·Event/record 삭제·Item
연결 해제는 계속 허용되며, 같은 날짜 draft append만 기존 `-1003`으로 거절된다. 없음·비소유는 404 `-404`, 이미 SAVED는
409 `-1003`(응답 유실 뒤 재시도한 클라이언트에게 "앞선 저장이 성공했다"는 신호), 잘못된 날짜 형식·
zero-byte body(Content-Type 유무 무관)·`emotionType` 누락/null/미지원 literal·깨진 JSON은 400 `-400`,
body는 있는데 Content-Type이 없거나 JSON이 아니면 415 `-415`다. **새 error code는 추가하지 않았다.**
commit 뒤 서버가 User Memory 갱신을 별도로 진행하지만
그 성패는 이 응답과 무관하며 클라이언트가 조회할 대상이 아니다. ID 기반 deprecated 경로는 만들지 않았다.

`POST /s/api/{version}/user-memory/updates/{taskId}/result`는 AI가 만든 새 User Memory 문서를 사용자
문서 전체와 교체하는 서버간 endpoint다. 성공·실패가 같은 경로로 오며 `status`가 갈래를 정한다(FAILED도
200 — DB는 바뀌지 않고 작업만 종결된다). draft 흐름과 달리 입력 조회·폴링·별도 callback이 없어 이
호출 하나가 결과 전달과 종료 통보를 겸하고, 그래서 token 재발급 지점도 없다(작업당 token 하나).
`Task-Token` 불일치는 401 `-1002`, 작업 없음·만료·이미 종결·중복 도착은 404 `-1001`, 접수 이후 다른
날짜의 갱신이 문서를 교체했으면 409 `-1017`, 계약 위반(status 누락·SUCCESS인데 문서 없음)은 400 `-400`이다.
**4xx는 전부 재시도해도 달라지지 않는 실패**이고 AI는 이를 재시도 중단 신호로 읽는다.

`/s/api/{version}/timeline/drafts/{taskId}`에는 AI 서버간 endpoint 셋이 있다 — `GET .../input`(정규 AI 입력
반환), `POST .../result`(Event/Item/junction 저장 + 채택 source 삭제), `POST .../callback`(작업 상태 전이).
세 endpoint 모두 현재 단계에서 받은 `Task-Token` header를 검증하고 Redis 내부 `ProcessStage`가 호출
순서를 제한한다. 입력·결과 성공 응답은 후속 요청용 `taskToken`을 body로 반환한다. 토큰 불일치는
401 `-1002`, 작업 없음·만료는 404 `-1001`, 현재 stage와 맞지 않는 입력·결과·callback 또는 상충
terminal callback은 409 `-1017`다. 이미 소비된 token의 입력·결과 재요청은 401이다.
계약 상세는 [ai-contract](ai-contract.md)가 소유한다.

`PUT/DELETE /a/api/{version}/push-registrations`는 FID(Firebase Installation ID)를 path/query가 아닌
request body(`firebaseInstallationId`)로 받는다 — access log·프록시 URL에 민감 opaque 식별자가 남지
않게 하는 의도적 계약이다(body는 access log masker가 마스킹). PUT은 등록·갱신·계정 전환 재결합의
멱등 upsert, DELETE는 (owner, FID) 동시 일치 시에만 지우는 멱등 해제다(미존재도 200).
`GET /a/api/{version}/push-settings`와 두 개의 `PUT` (`/enabled`, `/daily-reminder/enabled`)은 푸시
수신 설정의 서버 권위 계약이다(#314, #318). 세 operation 모두 `@LoginTermsExempt`라 약관 미동의
상태에서도 알림을 끌 수 있으며 bearer 인증은 그대로 요구한다. GET은
전체 ON/OFF와 일일 리마인더 ON/OFF·`HH:mm` 시각을 반환한다(순수 조회 — 행 생성은 가입 transaction과
rollout backfill이 소유한다). 행이 없으면 GET·PUT 모두 기본값으로 가리거나 조용한 no-op 없이 500이다 —
기본이 ON이라 가려버리면 "켜짐"이라 답하면서 실제로는 아무것도 발송되지 않는 상태가 된다.
`/enabled`는 **예정 알림 전체 스위치**이며 타임라인 완료 통지는 이 스위치를 따르지 않는다(#319 — 사용자가
직접 시작한 작업의 결과 통지). 종류가 하나뿐인 현재는 마스터 OFF와 리마인더 OFF의 효과가 같다.
일일 리마인더는 기본 ON이고 전체 사용자에게 매일 21:00(`Asia/Seoul`) 일괄 발송한다(#318) — 사용자
조작은 `/daily-reminder/enabled`로 끄고 켜는 것뿐이고, 응답의 `time`은 서버가 고정한 읽기 전용 값이다
(시각 변경 API 없음 — 앱의 "매일 21:00" 안내 문구용). 두 알림 모두 정보성 통지라 별도 수신 동의
절차를 두지 않으며 리마인더의 수신거부 수단은 일일 알림 OFF다 — 영리 목적의
광고성 알림을 추가하려면 정보통신망법 제50조가 요구하는 동의·야간 제한·표기·수신거부 수단을 함께
도입해야 한다.

`GET /a/api/{version}/users/me`는 토큰 응답과 분리된 인증 회원 본인 조회다. 응답 body 필드는
nullable `nickname` 하나이며 값이 없으면 key 생략이 아니라 명시적 JSON null이다. 다른 회원을 선택하는
parameter는 없고, 유효하게 서명된 토큰의 userId에 회원 행이 없으면 무토큰과 같은 401 `-2001`로 수렴해
탈퇴 여부·내부 식별자 존재를 노출하지 않는다. 토큰 response·JWT claim에 회원 정보를 싣지 않는다.

`DELETE /a/api/{version}/users/me`(#305)는 인증 회원 본인의 탈퇴 접수다. request body는 없고(유효한
bearer 인증이 본인 확인 수단) 첫 성공은 `202 Accepted + body=null`이다 — 202는 논리 탈퇴(이후 이
회원의 모든 `/a/api` 접근·token/refresh 발급 차단), 이 transaction이 관측한 기존 refresh 전량 폐기,
push 등록 삭제, 개인정보 삭제 작업의 durable 접수가 한 DB transaction으로 commit됐다는 뜻이며 MySQL
콘텐츠·Redis·S3의 물리 삭제 완료(#302 worker 책임)를 뜻하지 않는다. 이미 인증을 통과한 동시 요청은
같은 202로 멱등 수렴하고, commit 뒤 같은 access token의 새 요청은 401 `-2001`이다(응답을 잃은 앱은
401을 이미 탈퇴된 terminal 결과로 취급). 미인증/무효/만료/이미 최종 삭제된 회원도 401 `-2001`로
존재를 노출하지 않는다. `@LoginTermsExempt`라 약관 미동의 상태에서도 탈퇴할 수 있다. 내부
userId/subjectId/jobId는 응답·OpenAPI에 노출하지 않는다. 같은 소셜 계정의 다음 로그인은 과거
데이터·동의와 연결되지 않는 신규 가입으로 진행된다(재가입 차단·전용 오류 코드 없음). **새 error
code는 추가하지 않았다.**

`GET /api/{version}/terms?stage=LOGIN|TIMELINE_FIRST_CREATE`(#303)는 로그인 전 화면에서도 쓰는 public
약관 조회다(`PublicTermApi` — 보호 operation 목록 밖, bearer 문서 없음). `stage`는 필수 enum query이고
누락·미지원 값은 400 `-400`이다. 응답 `terms[]`는 종류별 현재 문서(`effectiveAt <= now(KST)` 최신
버전)를 서버 정의 화면 순서(`TermType.displayOrder`)로 담으며 각 원소는
`termType`·`version`·`title`·`contentUrl`·`required`·`effectiveAt`(offset 없는 KST LocalDateTime)이다.
응답에 약관 원문은 없다(#320) — `contentUrl`은 always-present non-null HTTPS URI이고 클라이언트가
WebView로 연다. 이 값은 문서 행에 저장된 게시 주소를 그대로 내려준 것이지 서버가 규칙으로 만든 값이
아니다(현재 게시 규약은 `https://laimory.app/terms/{종류}/{version}`이지만 운영 규약이며 서버가 강제하는
형식은 https 절대 URI뿐이다). `version`은 숫자가 아니라 `MAJOR.MINOR` 문자열(`1.0`)이며 서버는
파싱·정렬하지 않는다. `required`와 stage 소속은 DB 사본이 아니라 `TermType` enum mapping 값이다. 현재 유효 문서가 없으면
(activation 전 rollout) 404/500이 아니라 200과 `terms=[]`이고 일부 종류만 유효하면 그 문서만 반환한다.

`POST /a/api/{version}/terms/agreements`(#303)는 동의 일괄 등록이다(`TermAgreementApi` — 회원 account
도메인이라 hidden `@AuthenticationPrincipal Long userId`). body `agreements[]`의 각
`(termType, version)`은 조회 응답 값을 그대로 회신한다. 배열 누락/빈 배열·항목 필드 누락·동일 pair
중복·미지원 termType literal은 400 `-400`, 하나라도 존재하지 않거나 현재 버전이 아니면 전체 미기록 +
409 `-3002`(재조회 신호)다. 전부 현재 버전이면 한 DB transaction으로 기록하고 성공은 `200 + body=null`
이다. 수락 시각은 서버가 batch당 한 번 캡처한 KST 벽시계이며 같은 버전 재전송은 멱등 성공(최초 수락
시각 불변)이다. 동의 철회 API는 없다. `GET /a/api/{version}/terms/agreements`는 회원에게 남아 있는
전체 동의 이력을 `acceptedAt DESC`(PK DESC tie-breaker)로 반환한다 — 각 원소는 조회 응답과 같은 문서
필드(`contentUrl`은 동의한 그 버전 행에 저장된 URL이라 이후 게시 규약이 바뀌어도 변하지 않는다) +
`acceptedAt`이고, 이력이 없으면 404가 아니라 200과
`agreements=[]`다. 두 약관 GET response는 access log에서 privacy skeleton으로 마스킹되어 제목과
`contentUrl` 값이 남지 않는다([observability](../operations/observability.md)).

미동의 약관 gate: `/a/api` HandlerMethod는 기본으로 현재 `LOGIN` 필수 약관 동의를 요구하고(미동의
403 `-3001`), draft 생성·사진 presign은 `TIMELINE_FIRST_CREATE`를 추가 요구한다. exemption(회원 탈퇴 DELETE /me 포함)과 fail-open
계약은 [authentication runtime](../runtime/authentication.md)이 소유한다.

### Boundary conventions

- `*Api` interface가 HTTP signature와 OpenAPI annotation을 소유하고 controller가 구현한다.
- controller의 base path는 class-level `@RequestMapping(ApiUrls.*)`으로 선언한다.
- request body는 `...Request`, 외부 response 표현은 `...Response`로 끝낸다.
  service 내부 DTO와 request 안의 domain input element는 domain 이름을 쓸 수 있다.
- controller는 `ResponseEntity<T>`를 반환하고 JPA Entity를 직접 노출하지 않는다.

### Response

app-facing success/error는 다음 envelope를 사용한다.

```json
{
  "header": {
    "code": 0,
    "message": ""
  },
  "body": {
    "result": "..."
  }
}
```

- success code는 JSON integer `0`이고 성공 message는 항상 `""`다.
- 조회처럼 반환할 결과가 있는 성공은 typed response를 `body`에 담는다.
- 반환할 결과가 없는 성공도 envelope를 유지하며 `body` key를 명시적 JSON null로 반환한다.
- error code는 기존 번호를 음수화한 JSON integer이고 `body=null`이다.
- `/status`는 infrastructure probe를 위한 plain JSON으로 envelope 밖이다.
- AI callback은 성공 시 body 없는 HTTP 200을 반환한다. callback `errorCode`는 JSON integer다.
- 서버간 AI endpoint(`/s/api`)는 app envelope를 쓰지 않고 typed JSON을 직접 반환한다(에러만 envelope).
- Entity를 직접 반환하지 않고 response DTO를 사용한다.

### Errors

- service는 response를 만들지 않고 exception을 던진다.
- client가 구분해야 하는 domain rejection은 `BusinessException(ExceptionType)`이다.
  각 `ExceptionType`은 공개 `int code`, HTTP status와 access log level을 소유한다. 서로 다른 내부 타입이
  같은 code를 공유할 수 있다(예: `REFRESH_TOKEN_INVALID`/`REFRESH_TOKEN_REUSED` → 둘 다 `-2003`).
  numeric code를 타입으로 되돌리는 전역 lookup은 없고 task/callback 경계만 local allowlist를 소유한다.
- 모든 상태에서 잘못된 input은 `IllegalArgumentException`이며 400 `-400`
  (`VALIDATION_FAILED`)으로 매핑한다.
- `ResponseStatusException`을 domain service에서 사용하지 않는다.
- 내부 invariant failure는 catch-all 500 `-500`(`UNEXPECTED_ERROR`)으로 처리한다.
- MVC 표준 예외·RSE 브리지는 framework가 정한 HTTP status를 그대로 보존하고 envelope code만
  `ExceptionType.fromStatus` 폴백으로 정한다(406이 `-400`과 함께 나갈 수 있음).
- 새 code block을 할당할 때 기존 번호 블록을 보존한다. domain block 숫자는 HTTP status와 무관하며
  status는 enum field가 결정한다. `1006`, `1010`, `1012`, `1016` 번호는 재사용하지 않는다.
- 새 error는 `ExceptionType`에 code/status/logLevel을 추가하고 기본·ko·en message bundle을 함께 추가한다.
  같은 공개 code의 새 내부 원인은 새 타입으로 구분할 수 있지만 같은 status/message를 유지한다.
- message bundle 문구는 client에게 직접 노출되는 짧은 사용자 문구로 쓰고 내부 진단·운영 지침을 넣지 않는다.
  client는 message가 아니라 code로 분기한다. bundle key는 numeric code에서 `ERROR_XXXX`로 계산하므로
  같은 code는 같은 message를 사용한다(bundle key는 외부에 노출하지 않음).
- 사용자 입력을 message argument에 넣지 않는다.

### Transaction ID

서버가 요청마다 UUIDv7 transaction ID를 새로 만든다. client 제공값은 재사용하지 않는다.
외부 노출은 HTTP response header `Transaction-Id`뿐이며 envelope에는 넣지 않는다.

## Invariants

- API contract 변경은 `*Api`, DTO, controller/handler, OpenAPI와 tests를 함께 확인한다.
- error message보다 code가 client 분기 기준이다.
- log 정책(무엇을 남기고 무엇을 금지하는지)은 [observability](../operations/observability.md)가 소유한다.
- `/a/api`를 현재 enforcement만 보고 public API로 재정의하지 않는다.

## Known Gaps

- exhaustive generated API reference는 없으며 runtime OpenAPI가 field-level source다.

## Update When

prefix/versioning, endpoint signature, DTO/envelope, error mapping, authentication 표시 또는 transaction ID가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests '*ControllerTest' --tests '*ApiTest' --tests '*Error*Test'
```
