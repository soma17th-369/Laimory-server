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
| `/s/api/{version}` | server-to-server API, endpoint별 자체 인증 | callback token 등 endpoint가 강제 |
| `/a/api/{version}` | bearer-authenticated user API | security chain이 `authenticated()` 강제 — 무토큰/무효 토큰 401 `-2001` |

`version`은 `ApiUrls.VERSION` 정규식 path variable을 사용한다. controller는 값을 service로 전달하고
version별 동작은 service가 결정한다.

보호 operation 11개(timeline 9 + push-registrations PUT/DELETE)는 `bearerAuth` security requirement와
401 응답을 문서화하고, userId principal은 `@Parameter(hidden = true)`라 OpenAPI parameter에 나타나지
않는다(클라이언트 입력이 아님). 인증 흐름 상세는
[authentication runtime](../runtime/authentication.md)이 소유한다.

`GET /a/api/{version}/timeline/daily-records`는 인증 사용자의 DRAFT/SAVED DailyRecord 전체를
`recordDate DESC, dailyRecordId DESC` 순서로 반환한다. 기록이 없으면 200과 `timelines=[]`이며,
`GET /a/api/{version}/timeline/daily-records/{dailyRecordId}`는 없음·비소유를 같은 404 `-404`로
은닉한다. 두 응답 모두 Event별 연결 Item을 `events[].items[]`에 포함한다.

`PATCH /a/api/{version}/timeline/events/{timelineEventId}`는 기존 Event 상세 편집 endpoint 하나에서
`title`·`subtitle`·`startAt`·`endAt`(네 key 모두 필수), 선택적 `eventType`, 선택적 `memo`와 선택적
`photosToAdd`를 처리한다. `memo` 부재는 변경 없음이고 null·blank는 제거다. `photosToAdd` 부재 또는 빈
배열은 Item 변경 없음이며 날짜 guard도 취득하지 않고, 명시적 null은 400이다. 배열 원소는
`rawId`·`startAt`·`endAt`과 PHOTO payload(`filename`, `clientPhotoUri`, `latitude`, `longitude`)만 받는다 —
`description`과 `photoUrl`은 입력 계약에 없다. non-empty 추가는 Event/memo 변경과 PHOTO Item/junction 저장을
한 DB transaction으로 commit하며 guard 충돌은 409 `-1016`이다. 성공 응답은
`200 + ApiResponse<Void>`이고 `body=null`이다. 신규 PHOTO의 서버 ID가 필요하면 DailyRecord 단건 GET으로
권위 상태를 다시 조회한다. 별도 PHOTO 추가 endpoint는 없고
`PUT .../events/{timelineEventId}/memo`도 memo만 교체하는 현재 지원 API이며 성공 응답은 동일하게
`body=null`이다. 기존 operation을 확장한 것이므로 보호 operation 수는 11개로 유지된다.

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
  status는 enum field가 결정한다. `1006`, `1010` 결번은 재사용하지 않는다.
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
