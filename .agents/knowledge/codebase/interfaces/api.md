# HTTP API Contract

## Scope

HTTP path, versioning, response envelope, error, authentication 표시와 transaction ID 계약을 설명한다.

## Read When

endpoint, DTO, HTTP status, error code/message, OpenAPI annotation 또는 transaction ID 노출을 바꿀 때 읽는다.

## Authoritative Sources

- `com.laimory.server.common.ApiUrls`
- 각 feature의 `*Api.java`, controller, request/response DTO
- `ApiResponse`, `ErrorCode`, `ExceptionType`, `GlobalExceptionHandler`
- `OpenApiConfig`, API/controller/error contract tests
- 실행 중 생성되는 OpenAPI 문서

수동 endpoint·field 목록보다 위 source가 우선한다.

## Current Implementation

### Path spaces

| Prefix | Contract | Current enforcement |
|---|---|---|
| `/api/{version}` | 인증 없는 app-facing public API | public |
| `/s/api/{version}` | server-to-server API, endpoint별 자체 인증 | callback token 등 endpoint가 강제 |
| `/a/api/{version}` | bearer-authenticated user API | security chain이 `authenticated()` 강제 — 무토큰/무효 토큰 401 `ERROR_2001` |

`version`은 `ApiUrls.VERSION` 정규식 path variable을 사용한다. controller는 값을 service로 전달하고
version별 동작은 service가 결정한다.

보호 operation 11개(timeline 9 + push-registrations PUT/DELETE)는 `bearerAuth` security requirement와
401 응답을 문서화하고, userId principal은 `@Parameter(hidden = true)`라 OpenAPI parameter에 나타나지
않는다(클라이언트 입력이 아님). 인증 흐름 상세는
[authentication runtime](../runtime/authentication.md)이 소유한다.

`GET /a/api/{version}/timeline/daily-records`는 인증 사용자의 DRAFT/SAVED DailyRecord 전체를
`recordDate DESC, dailyRecordId DESC` 순서로 반환한다. 기록이 없으면 200과 `timelines=[]`이며,
`GET /a/api/{version}/timeline/daily-records/{dailyRecordId}`는 없음·비소유를 같은 404 `ERROR_0404`로
은닉한다. 두 응답 모두 Event별 연결 Item을 `events[].items[]`에 포함한다.

`PATCH /a/api/{version}/timeline/events/{timelineEventId}`는 기존 Event 상세 편집 endpoint 하나에서
`title`·`subtitle`·`startAt`·`endAt`(네 key 모두 필수), 선택적 `eventType`, 선택적 `memo`와 선택적
`photosToAdd`를 처리한다. `memo` 부재는 변경 없음이고 null·blank는 제거다. `photosToAdd` 부재 또는 빈
배열은 Item 변경 없음이며 날짜 guard도 취득하지 않고, 명시적 null은 400이다. 배열 원소는
`rawId`·`startAt`·`endAt`과 PHOTO payload(`filename`, `clientPhotoUri`, `latitude`, `longitude`)만 받는다 —
`description`과 `photoUrl`은 입력 계약에 없다. non-empty 추가는 Event/memo 변경과 PHOTO Item/junction 저장을
한 DB transaction으로 commit하며 guard 충돌은 409 `ERROR_1016`이다. 응답은 수정된 Event와 연결된 전체
Item을 반환한다. 별도 PHOTO 추가 endpoint는 없고 기존 `PUT .../events/{timelineEventId}/memo`는 호환용으로
유지하되 OpenAPI에서 deprecated다. 기존 operation을 확장한 것이므로 보호 operation 수는 11개로 유지된다.

`PUT/DELETE /a/api/{version}/push-registrations`는 FID(Firebase Installation ID)를 path/query가 아닌
request body(`firebaseInstallationId`)로 받는다 — access log·프록시 URL에 민감 opaque 식별자가 남지
않게 하는 의도적 계약이다(body는 access log masker가 마스킹). PUT은 등록·갱신·계정 전환 재결합의
멱등 upsert, DELETE는 (owner, FID) 동시 일치 시에만 지우는 멱등 해제다(미존재도 200).

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
    "code": "COMMON_0000",
    "message": "..."
  },
  "body": {}
}
```

- success code는 `COMMON_0000`이다.
- error code는 `ERROR_`로 시작하고 `body=null`이다.
- `/status`는 infrastructure probe를 위한 plain JSON으로 envelope 밖이다.
- AI callback은 성공 시 body 없는 HTTP 200을 반환한다.
- Entity를 직접 반환하지 않고 response DTO를 사용한다.

### Errors

- service는 response를 만들지 않고 exception을 던진다.
- client가 구분해야 하는 domain rejection은 `BusinessException(ExceptionType)`이다.
  `ExceptionType`은 내부 실패 사유(access log level 소유)이고, client 노출 code·HTTP status는
  타입이 참조하는 `ErrorCode`가 결정한다 — **N:1 매핑**이라 같은 code라도 내부 사유·심각도가
  다를 수 있다(예: `REFRESH_TOKEN_INVALID`/`REFRESH_TOKEN_REUSED` → 둘 다 `ERROR_2003`).
- 모든 상태에서 잘못된 input은 `IllegalArgumentException`이며 400 `ERROR_0400`
  (`VALIDATION_FAILED`)으로 매핑한다.
- `ResponseStatusException`을 domain service에서 사용하지 않는다.
- 내부 invariant failure는 catch-all 500 `ERROR_0500`(`UNEXPECTED_ERROR`)으로 처리한다.
- MVC 표준 예외·RSE 브리지는 framework가 정한 HTTP status를 그대로 보존하고 envelope code만
  `ExceptionType.fromStatus` 폴백으로 정한다(406이 `ERROR_0400`과 함께 나갈 수 있음 —
  `ErrorCode.status()`는 `BusinessException` 경로의 SSOT).
- `ErrorCode` 이름은 배포 후 client contract라 rename하지 않는다.
- 새 code block을 할당할 때 `ErrorCode` 상단 block registry를 따른다.
  domain block 숫자는 HTTP status와 무관하며 status는 enum field가 결정한다.
- 새 error 추가는 세 경우로 나뉜다:
  ① 새로 throw되는 공개 error = `ErrorCode` + 기본·ko·en message bundle + `ExceptionType` 한 줄
  ② 기존 공개 응답의 새 내부 원인 = `ExceptionType` 한 줄만
  ③ 폴링 데이터/링크 파라미터 전용 = `ErrorCode`(+bundle)만 — throw되지 않으면 `ExceptionType` 불필요
- message bundle 문구는 client에게 직접 노출되는 짧은 사용자 문구로 쓰고 내부 진단·운영 지침을 넣지 않는다.
  client는 message가 아니라 code로 분기한다. message는 `ErrorCode` 이름이 key라
  같은 code=같은 message가 구조적으로 보장된다(내부 구분을 응답 문구로 유출하지 않음).
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
