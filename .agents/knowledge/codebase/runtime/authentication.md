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

## Intended Contract

- `/a/api/{version}`은 사용자가 `Authorization: Bearer <access-token>`으로 접근하는 인증 영역이다.
- OpenAPI의 `bearerAuth`와 timeline API `@SecurityRequirement`는 이 공개 계약을 유지한다.
- 인증된 principal의 userId가 service로 전달돼 고정 fallback을 대체해야 한다.

## Current Implementation

두 security chain이 있다.

- OAuth handshake 경로(`/oauth2/**`, `/login/**`)는 Redis-backed HTTP session을 쓴다.
- 그 외 API chain은 stateless지만 현재 `anyRequest().permitAll()`이다.

구현된 로그인·token 기능:

1. Google/Kakao OIDC login에서 provider `sub`로 user를 찾거나 만든다.
2. 앱이 verifier에서 만든 challenge로 login 시작 주체를 바인딩한다.
3. login 성공 뒤 60초 App Code를 Redis hash key로 저장하고 GETDEL로 소비한다.
4. 교환 성공 시 자체 access JWT와 opaque refresh token을 발급한다.
5. access token은 HS256이며 기본 15분, `iss/sub/iat/exp` claim만 둔다. 서버에 저장하지 않는다.
6. refresh token은 기본 30일이며 DB에 SHA-256 hash만 저장한다.
7. refresh는 rotation되고 reuse가 탐지되면 해당 user의 refresh token을 모두 revoke한다.
8. logout은 전달된 refresh token을 revoke한다.
9. `JwtTokens.parseUserId`로 서명·만료를 검증하고 subject userId를 읽는 기능은 있다.

## Current Enforcement

`SecurityConfig`가 `/a/api`를 포함한 모든 일반 API를 `permitAll`한다.
JWT request filter, SecurityContext authentication과 controller/service userId propagation은 없다.

## Current Fallback

timeline draft·photo·callback/finalize/polling은 `TimelineDefaults.DEFAULT_USER_ID=0`을 사용한다.
이는 인증 계약이 구현됐다는 뜻이 아니라 enforcement 전 임시 호환 동작이다.

## Invariants

- Swagger bearer contract를 현재 `permitAll` 때문에 제거하지 않는다.
- provider account는 email이 아니라 `(provider, provider_user_id)`로 식별한다.
- access token에 PII를 넣거나 raw refresh/App Code를 저장하지 않는다.
- refresh rotation/reuse detection과 App Code one-time consumption의 atomicity를 보존한다.

## Known Gaps

- JWT request filter와 `/a/api` authorization enforcement
- SecurityContext에서 request userId를 service로 전달하는 경로
- `DEFAULT_USER_ID=0` 제거

이 gap은 별도 인증 enforcement 작업으로 다룬다. 문서 개편에서 API를 공개 영역으로 재정의하지 않는다.

## Update When

Security chain, protected prefix, token claim/TTL/storage, provider validation, refresh behavior,
principal propagation 또는 fallback이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.auth.*' --tests 'com.laimory.server.config.*'
```
