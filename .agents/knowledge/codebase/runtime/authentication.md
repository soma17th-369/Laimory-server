# Authentication Runtime

## Scope

OAuth login, app handoff, 자체 access/refresh token과 `/a/api` authentication 상태를 설명한다.

## Read When

Security filter chain, OAuth provider, JWT claim, refresh rotation, app code 또는 request userId를 바꿀 때 읽는다.

## Authoritative Sources

- `SecurityConfig`, `OAuth2LoginSecurityConfig`, `OpenApiConfig`
- `src/main/java/com/laimory/server/auth/**`, `user/**`
- auth/user entities, repositories, Redis stores and tests
- `application*.properties`

## Current Contract

- `/a/api/{version}`은 사용자가 `Authorization: Bearer <access-token>`으로 접근하는 인증 영역이다.
  유효한 자체 access JWT 없이는 401 `-2001` envelope로 거절된다(`WWW-Authenticate: Bearer`).
- OpenAPI의 `bearerAuth`, timeline API `@SecurityRequirement`와 보호 operation의 401 문서가 이 계약을 표현한다.
- 인증된 principal(`Long` userId)이 controller `@AuthenticationPrincipal`로 주입돼 service까지 전달된다.

## Current Implementation

두 security chain이 있다.

- OAuth handshake 경로(`/oauth2/**`, `/login/**`)는 Redis-backed HTTP session을 쓴다.
- 그 외 API chain은 stateless다. `/a/api`(정확한 prefix와 하위 경로만 — `/a/apiary` 미매칭)는
  `authenticated()`, 나머지는 `permitAll()`이다(denyAll로 잠그지 않는다 — 미매핑 404 계약 보존).

`/a/api` 인증 흐름:

1. `JwtAuthenticationFilter`(security chain 내부, `AuthorizationFilter` 앞)가 Bearer token을
   `JwtTokens.parseUserId`로 검증해 성공 시 `Long` userId principal 인증을 SecurityContext에 넣는다.
   부재/형식 불량/무효/만료는 사유 구분 없이 context 없이 통과시킨다(token 원문은 어디에도 보존 안 함).
2. 인가 단계에서 무인증이면 `ApiAuthenticationEntryPoint`가 401 `-2001` envelope를 직접 쓴다
   (Security filter 단계는 `GlobalExceptionHandler` 미도달 — `AppChallengeFilter` 400 작성과 같은 선례).
   `ExceptionType.API_AUTHENTICATION_REQUIRED`(INFO)를 request attribute에 심어 access log와 합류하고,
   `Transaction-Id` 헤더는 전역 `TransactionIdFilter`가 이미 보장한다.
3. filter/EntryPoint는 `SecurityConfig`에 빈으로 선언하고 Boot 전역 servlet filter 자동 등록은
   `FilterRegistrationBean#setEnabled(false)`로 끈다.

`JwtTokens`는 양수 userId만 발급·인증한다 — 0·음수 subject는 유효한 서명이 있어도 실패 처리해
과거 user 0 데이터 접근을 차단한다. 매 요청 user row 조회는 없다(stateless access token 계약 유지).

구현된 로그인·token 기능:

1. Google/Kakao OIDC login에서 provider `sub`로 user를 찾거나 만든다.
   - Kakao scope는 `openid,profile_nickname`이다. 닉네임은 검증된 id_token의 `nickname` claim에서
     읽고(blank·비문자열은 null) UserInfo endpoint는 호출하지 않는다. email은 수집하지 않는다(콘솔 권한 없음).
   - 기존 Kakao 사용자는 재로그인 시 non-null 닉네임으로 갱신하고 누락 claim은 기존 값을 보존한다.
     Google 기존 사용자는 갱신 없이 반환한다.
2. 앱이 verifier에서 만든 challenge로 login 시작 주체를 바인딩한다.
3. login 성공 뒤 60초 App Code를 Redis hash key로 저장하고 GETDEL로 소비한다.
4. 교환 성공 시 자체 access JWT와 opaque refresh token을 발급한다.
5. access token은 HS256이며 기본 15분, `iss/sub/iat/exp` claim만 둔다. 서버에 저장하지 않는다.
6. refresh token은 기본 30일이며 DB에 SHA-256 hash만 저장한다.
7. refresh는 rotation되고 reuse가 탐지되면 해당 user의 refresh token을 모두 revoke한다.
8. logout은 전달된 refresh token을 revoke한다.
9. `JwtTokens.parseUserId`로 서명·만료를 검증하고 subject userId를 읽는 기능은 있다.

OAuth/OIDC 핸드셰이크 실패, 성공 handler의 handoff context 누락과 로그인 완료 예외는 모두
`?error=-2004`로 앱에 redirect한다. 세 실패 경로는 `ExceptionType.OAUTH_LOGIN_FAILED.code()`에서 값을
파생하며 session invalidation과 302를 보존한다. provider/OIDC 실패 WARN, context 누락 WARN,
로그인 완료 예외 ERROR+stacktrace 진단 로그는 서로 독립적으로 유지한다. 정상 성공은 `app_code`
handoff를 그대로 사용한다.

## userId Propagation

- 13개 timeline 보호 API(`draft 생성/photo presign/polling/진행 작업 목록` + `DailyRecord 전체/날짜 단건/
  deprecated ID 단건 조회` + `Event 조회/수정/메모/삭제` + `DailyRecord 날짜/deprecated ID 삭제`)의
  controller가 principal userId를 service 체인에 전달한다 — draft/record/staging/S3 key/
  polling·직접 조회 결과가 전부 같은 userId에 귀속된다.
- Redis draft task는 owner(`userId`)를 세 상태(PROCESSING/SUCCESS/FAILED) 모두 보존한다.
  polling은 상태 분기 전에 owner를 대조하고 타 사용자 task는 404 `-1001`로 은닉한다.
- 진행 작업 목록은 principal userId로만 사용자별 index key를 조립하고(client 제공 userId 없음), 후보
  task JSON의 owner를 재검증한다 — 타 사용자 task는 오류 없이 제외해 존재까지 비노출한다.
- AI callback(`/s/api`)은 Bearer 대상이 아니다 — request principal 없이 task 저장 owner로
  Redis terminal 전이를 수행한다.
- 고정 fallback(`TimelineDefaults.DEFAULT_USER_ID=0`)은 제거됐다. 기존 user 0 데이터는 인증 API에서
  조회·귀속되지 않는다(자동 이전·삭제 없음 — staging은 기존 retention cleanup 대상).

## Invariants

- provider account는 email이 아니라 `(provider, provider_user_id)`로 식별한다.
- access token에 PII를 넣거나 raw refresh/App Code를 저장하지 않는다.
- refresh rotation/reuse detection과 App Code one-time consumption의 atomicity를 보존한다.
- 401 응답·로그에 token 원문, Authorization 헤더, parse 실패 상세를 남기지 않는다.
- principal은 별도 래퍼 없는 `Long` userId다 — `@AuthenticationPrincipal(errorOnInvalidType = true) Long`과
  1:1이어야 한다(String principal을 만드는 테스트 헬퍼 `user()` 사용 금지, `AuthTestSupport` 사용).

## Update When

Security chain, protected prefix, token claim/TTL/storage, provider validation, refresh behavior,
principal propagation 또는 fallback이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.auth.*' --tests 'com.laimory.server.config.*'
```
