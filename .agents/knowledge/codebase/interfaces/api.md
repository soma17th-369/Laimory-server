# HTTP API Contract

## Scope

HTTP path, versioning, response envelope, error, authentication 표시와 transaction ID 계약을 설명한다.

## Read When

endpoint, DTO, HTTP status, error code/message, OpenAPI annotation 또는 transaction ID 노출을 바꿀 때 읽는다.

## Authoritative Sources

- `com.laimory.server.common.ApiUrls`
- 각 feature의 `*Api.java`, controller, request/response DTO
- `ApiResponse`, `ErrorCode`, `GlobalExceptionHandler`
- `OpenApiConfig`, API/controller/error contract tests
- 실행 중 생성되는 OpenAPI 문서

수동 endpoint·field 목록보다 위 source가 우선한다.

## Current Implementation

### Path spaces

| Prefix | Contract | Current enforcement |
|---|---|---|
| `/api/{version}` | 인증 없는 app-facing public API | public |
| `/s/api/{version}` | server-to-server API, endpoint별 자체 인증 | callback token 등 endpoint가 강제 |
| `/a/api/{version}` | bearer-authenticated user API | 현재 security chain은 `permitAll` |

`version`은 `ApiUrls.VERSION` 정규식 path variable을 사용한다. controller는 값을 service로 전달하고
version별 동작은 service가 결정한다.

`/a/api`의 intended bearer contract와 Swagger `bearerAuth`는 유지한다.
현재 JWT filter/userId propagation 부재는 [authentication runtime](../runtime/authentication.md)의 known gap이다.

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
- client가 구분해야 하는 domain rejection은 `BusinessException(ErrorCode)`이다.
- 모든 상태에서 잘못된 input은 `IllegalArgumentException`이며 400 `ERROR_0400`으로 매핑한다.
- `ResponseStatusException`을 domain service에서 사용하지 않는다.
- 내부 invariant failure는 catch-all 500 `ERROR_0500`으로 처리한다.
- `ErrorCode` 이름은 배포 후 client contract라 rename하지 않는다.
- 새 code block을 할당할 때 `ErrorCode` 상단 block registry를 따른다.
  domain block 숫자는 HTTP status와 무관하며 status는 enum field가 결정한다.
- 새 error code는 기본·ko·en message bundle 모두에 추가한다.
- message bundle 문구는 client에게 직접 노출되는 짧은 사용자 문구로 쓰고 내부 진단·운영 지침을 넣지 않는다.
  client는 message가 아니라 code로 분기한다.
- 사용자 입력을 message argument에 넣지 않는다.

### Transaction ID

서버가 요청마다 UUIDv7 transaction ID를 새로 만든다. client 제공값은 재사용하지 않는다.
외부 노출은 HTTP response header `Transaction-Id`뿐이며 envelope에는 넣지 않는다.

## Invariants

- API contract 변경은 `*Api`, DTO, controller/handler, OpenAPI와 tests를 함께 확인한다.
- error message보다 code가 client 분기 기준이다.
- query string, token, body, presigned URL을 API log에 남기지 않는다.
- `/a/api`를 현재 enforcement만 보고 public API로 재정의하지 않는다.

## Known Gaps

- `/a/api` request authentication/userId propagation이 구현되지 않았다.
- exhaustive generated API reference는 없으며 runtime OpenAPI가 field-level source다.

## Update When

prefix/versioning, endpoint signature, DTO/envelope, error mapping, authentication 표시 또는 transaction ID가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests '*ControllerTest' --tests '*ApiTest' --tests '*Error*Test'
```
