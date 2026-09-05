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
  #305부터 서명·만료에 더해 요청마다 회원 `ACTIVE`를 확인한다(#429부터 공유 Redis 캐시 경유 —
  miss만 users PK 조회) — 회원 없음과 `WITHDRAWAL_PENDING`(탈퇴 접수)은 token 상세와 구분 없이 같은
  401 `-2001`로 수렴하고, 상태 조회 DB 장애(캐시 miss 경로)만 fail-closed 500 `-500`이다(장애를
  credential 오류로 숨기지 않음 — hit는 DB를 호출하지 않고, Redis 장애는 miss로 강등해 DB 직행).
- OpenAPI의 `bearerAuth`, timeline API `@SecurityRequirement`와 보호 operation의 401 문서가 이 계약을 표현한다.
- 인증 principal은 `Long` userId다. timeline/push/initializer/onboarding controller의 콘텐츠 owner
  parameter는 `@CurrentSubject UUID subjectId`이며 MVC resolver가 principal을 subject mapping으로
  변환해 주입한다.
  회원 account controller(`GET/DELETE /a/api/{version}/user`)는 subject 변환 없이 hidden
  `@AuthenticationPrincipal Long userId`를 직접 받는다.

## Current Implementation

두 security chain이 있다.

- OAuth handshake 경로(`/oauth2/**`, `/login/**`)는 Redis-backed HTTP session을 쓴다.
- 그 외 API chain은 stateless다. `/a/api`(정확한 prefix와 하위 경로만 — `/a/apiary` 미매칭)는
  `authenticated()`, 나머지는 `permitAll()`이다(denyAll로 잠그지 않는다 — 미매핑 404 계약 보존).

`/a/api` 인증 흐름:

1. `JwtAuthenticationFilter`(security chain 내부, `AuthorizationFilter` 앞)가 Bearer token을
   `JwtTokens.parseUserId`로 검증하고, 성공 시 `UserAccountAccessService#isActive(userId)`까지 통과한
   경우에만 `Long` userId principal 인증을 SecurityContext에 넣고 userId 로그 attribute를 심는다(#305).
   이 검사는 #429부터 `RedisActiveStatusCache`를 탄다 — hit는 공유 Redis만 보고, miss는 users PK
   existence 조회 뒤 ACTIVE=true만 적재한다(음성 미캐시; 규칙·정책은 아래 "탈퇴 차단 정책(#429)").
   부재/형식 불량/무효/만료/비활성 회원은 사유 구분 없이 context
   없이 통과시킨다(token 원문은 어디에도 보존 안 함). 상태 조회 DB 장애(miss 경로)만 예외다 — 공용
   `ApiErrorResponseWriter`로 fail-closed 500 `-500` envelope과 ERROR 관측(stacktrace + access log
   attribute)을 남기고 chain을 중단한다(Redis 장애는 500이 아니라 miss 강등 후 DB 직행이다).
2. 인가 단계에서 무인증이면 `ApiAuthenticationEntryPoint`가 401 `-2001` envelope를 직접 쓴다
   (Security filter 단계는 `GlobalExceptionHandler` 미도달 — `AppChallengeFilter` 400 작성과 같은 선례).
   `ExceptionType.API_AUTHENTICATION_REQUIRED`(INFO)를 request attribute에 심어 access log와 합류하고,
   `Transaction-Id` 헤더는 전역 `TransactionIdFilter`가 이미 보장한다.
3. filter/EntryPoint는 `SecurityConfig`에 빈으로 선언하고 Boot 전역 servlet filter 자동 등록은
   `FilterRegistrationBean#setEnabled(false)`로 끈다.

`JwtTokens`는 양수 userId만 발급·인증한다 — 0·음수 subject는 유효한 서명이 있어도 실패 처리해
과거 user 0 데이터 접근을 차단한다. access token 자체는 여전히 서버 미저장 stateless다. #305가
`/a/api`마다 추가했던 users PK 조회 1회는 #429가 예고대로("실측 후 별도 이슈에서 상태 캐시 설계")
공유 Redis 상태 캐시로 대체했다 — DB 조회는 캐시 miss와 발급·회전 경로에만 남는다.

### 탈퇴 차단 정책(#429) — #305 §5.3 "매 요청 확인·즉시 차단"의 명시적 완화

필터 ACTIVE 검사의 캐시 규칙: **ACTIVE=true만** 캐시(음성 미적재 — `@Cacheable`의 `unless`),
무효화는 탈퇴 orchestrator의 commit 후 **evict 하나**(갱신 경로 없음), TTL 15분은 evict 유실 대비
안전망으로 **쓰기 시점 고정**(조회가 연장하지 않음)이다. 발급·회전(`AuthTokenService`)은 캐시 없이
`UserAccountService`로 DB 직행하며, 이 경계는 `AuthContextCacheAccessArchTest`가 빌드에서 강제한다.

탈퇴 시점 기준의 유한한 hard bound는 선언하지 않는다(fencing·발급 직렬화·완료시간 상한 비채택의
귀결). 대신 세 가지를 보장한다: ① 탈퇴 커밋·evict 뒤 **시작된** 필터 검사(miss→DB)·발급·회전은
결정적으로 거절된다 — 노출을 만드는 것은 커밋 전에 검사를 통과한 in-flight 작업의 산물뿐이다.
② 각 access token은 실제 발급 시각 + `app.auth.jwt.access-ttl`(기본 15m) + 만료 leeway 60s까지만
유효하고, stale 캐시 엔트리도 각각 적재 시각 + TTL에 소멸한다. ③ in-flight 발급이 늦게 저장한
refresh의 재회전은 DB 직행 검사가 거절하므로 회전 사슬은 1회로 종결된다(연장 불가). TTL은 지배적
실용 상한(기대치)이다. 응답 계약(401 `-2001` 수렴·존재 비노출)은 변하지 않았다.

인증을 통과한 `/a/api` HandlerMethod에는 약관 gate(#303)가 이어진다 — `TermsEnforcementInterceptor`가
controller 진입 전에 SecurityContext의 `Long` principal로 현재 `LOGIN` 필수 약관 동의를 검사하고
미동의는 403 `-3001`이다(401 인증 계약과 독립 — bearer 실패가 항상 먼저다). exemption은 raw path
allowlist가 아니라 `*Api` interface method의 `@LoginTermsExempt`뿐이다(동의 등록/이력·회원 조회 GET
/user·회원 탈퇴 DELETE /user(#305 — 미동의 사용자도 탈퇴 가능)·push-registrations PUT/DELETE·
push-settings 3종·앱 초기화 GET /initializer와 온보딩 완료 POST /onboarding/complete(#382 — 앱 온보딩은
약관 동의와 독립된 절차라 미동의 상태에서도 시작 화면을 분기하고 온보딩을 마쳐야 한다)). draft 생성·사진 presign은
`@RequiredTermsStage(TIMELINE_FIRST_CREATE)`로 단계를 추가 검사한다. 판정은 요청 시점 DB 권위이고
TTL cache가 없다 — 요청당 전 종류 current 요약을 catalog snapshot 1쿼리로 떠서(request attribute
캐시, #428) 요청이 강제하는 stage들과 조건부 위치약관 판정이 공유하고, 동의는 ready stage 필수 문서
id 합집합의 existence 1쿼리(+조건부는 별도 1쿼리)로 확인한다. 판정 시각 계약: 요청당 첫 판정 시점에
캡처한 snapshot이 그 요청 전체의 판정 권위라 요청 도중 발효된 문서는 다음 요청부터 강제된다. catalog
미준비 stage(기대 필수 종류의 current 문서 누락 — seed/activation 전, 또는 미지 `term_type` literal
때문에 current 조회에서 빠진 경우)는 부분 강제 없이 전체 fail-open한다(`TermCatalogReadiness`
metric·bounded log 경보).
token refresh/logout은 public auth 경로라 이 interceptor 대상이 아니다.

구현된 로그인·token 기능:

1. Google/Kakao OIDC login에서 provider `sub`로 user를 찾거나 만든다.
   - Google scope는 `openid,profile`이며, 예상 밖 email claim이 와도 저장하지 않는다.
   - Kakao scope는 `openid,profile_nickname`이다. 닉네임은 검증된 id_token의 `nickname` claim에서
     읽고(blank·비문자열은 null) UserInfo endpoint는 호출하지 않는다. email은 수집하지 않는다(콘솔 권한 없음).
   - 기존 Kakao 사용자는 재로그인 시 non-null 닉네임으로 갱신하고 누락 claim은 기존 값을 보존한다.
     갱신은 entity 저장이 아니라 `(provider, provider_user_id, status=ACTIVE)` 조건의 nickname-only
     UPDATE다(#305 — 탈퇴와 겹친 stale 로그인이 status/released identity를 되살리지 못함, 영향 0행은
     갱신 폐기). Google 기존 사용자는 갱신 없이 반환한다.
   - 신규 사용자 생성은 `NewUserProvisioner`의 단일 transaction이 user insert와 subject mapping
     insert(#282, `user_subject_links`)를 함께 commit/rollback한다 — 실패 시 부분 user나 orphan
     mapping이 남지 않는다. `UserService.findOrCreate`의 무트랜잭션 catch-재조회 동시 로그인
     수렴은 유지된다(UNIQUE 패자는 provisioner transaction 전체가 rollback된 뒤 승자 행으로 수렴).
2. 앱이 verifier에서 만든 challenge로 login 시작 주체를 바인딩한다.
3. login 성공 뒤 60초 App Code를 Redis hash key로 저장하고 GETDEL로 소비한다.
4. 교환 성공 시 자체 access JWT와 opaque refresh token을 발급한다.
5. access token은 HS256이며 기본 15분, `iss/sub/iat/exp` claim만 둔다. 서버에 저장하지 않는다.
   app-code 교환(`/auth/token`)과 refresh 회전은 발급 전에 회원 `ACTIVE`를 조회한다(#305) — 회원
   없음/`WITHDRAWAL_PENDING`은 각각 기존 `APP_CODE_INVALID` 401 `-2002`/`REFRESH_TOKEN_INVALID` 401
   `-2003`(INFO)으로 수렴하고 신규 탈퇴 전용 code는 없다. WARN은 실제 verifier 불일치와 active 회원의
   refresh 재사용 탐지만 유지한다. 이 발급 전 검사는 #429 캐시를 타지 않는 DB 직행이다(위 "탈퇴
   차단 정책"). 검사 통과 직후 탈퇴와 겹친 in-flight 발급은 허용된 제한 예외이며 그 token도 `/a/api`
   ACTIVE 검사(탈퇴 evict 뒤 miss부터)와 다음 회전 검사(DB 직행)에서 거절된다.
6. refresh token은 기본 30일이며 DB에 SHA-256 hash만 저장한다.
7. refresh는 rotation되고 reuse가 탐지되면 해당 user의 refresh token을 모두 revoke한다.
8. logout은 전달된 refresh token을 revoke한다.
9. `JwtTokens.parseUserId`로 서명·만료를 검증하고 subject userId를 읽는 기능은 있다.

OAuth/OIDC 핸드셰이크 실패, 성공 handler의 handoff context 누락과 로그인 완료 예외는 모두
`?error=-2004`로 앱에 redirect한다. 세 실패 경로는 `ExceptionType.OAUTH_LOGIN_FAILED.code()`에서 값을
파생하며 session invalidation과 302를 보존한다. provider/OIDC 실패 WARN, context 누락 WARN,
로그인 완료 예외 ERROR+stacktrace 진단 로그는 서로 독립적으로 유지한다. 정상 성공은 `app_code`
handoff를 그대로 사용한다.

## Principal and Subject Propagation

- timeline/push 보호 API의 `@CurrentSubject` MVC resolver는 SecurityContext의 raw `Long` principal을
  `SubjectMappingService.getRequired`로 매 요청 한 번 해석한다. mapping 누락·서비스 부재는 자동 생성
  없이 fail-closed하고, controller부터 service/repository까지 Java `UUID`만 전달한다.
- Redis draft task는 owner(UUIDv4 subject)를 세 상태(PROCESSING/SUCCESS/FAILED) 모두 보존한다.
  polling은 상태 분기 전에 owner를 대조하고 타 사용자 task는 404 `-1001`로 은닉한다.
- 진행 작업 목록은 request subjectId의 canonical UUID 문자열로 subject index key를 조립하고(client 제공
  식별자 없음), 후보
  task JSON의 owner를 재검증한다 — 타 사용자 task는 오류 없이 제외해 존재까지 비노출한다.
- AI callback(`/s/api`)은 Bearer 대상이 아니다 — request principal 없이 task 저장 owner로
  Redis terminal 전이와 subject 기반 FID 조회를 수행한다.
- 고정 fallback(`TimelineDefaults.DEFAULT_USER_ID=0`)은 제거됐다. 기존 user 0 데이터는 인증 API에서
  조회·귀속되지 않는다(자동 이전·삭제 없음 — staging은 기존 retention cleanup 대상).
- 콘텐츠 subject는 JWT principal이 아니다. 인증 filter와 access/refresh 도메인은 raw `Long` userId를
  유지하고, 콘텐츠 API의 MVC 경계 뒤에서만 UUID subjectId를 사용한다.
- 회원 account API(`GET/DELETE /a/api/{version}/user`)는 principal userId를 직접 받는다. GET은
  users 행을 endpoint 안에서 조회하며, 유효 토큰이라도 행이 없으면 무토큰과 같은 401 `-2001`로
  수렴한다(존재 비노출). DELETE(#305 탈퇴)는 단일 DB transaction으로 조건부
  `ACTIVE → WITHDRAWAL_PENDING` + 탈퇴 시각 + `provider_user_id` NULL release + push 마스터 OFF +
  일일 알림 OFF + userId-only PENDING 삭제 작업(insert-if-absent)을 commit하고
  202를 반환한다. **이 transaction은 행을 지우지 않는다**(#367) — refresh 행과 push 등록(FID)은
  보존되고, old credential의 사용·연장 차단은 요청·발급 전 `ACTIVE` 검사가 담당한다 — 필터 경로는
  commit 후 캐시 evict부터, 발급·회전은 DB 직행(#429 "탈퇴 차단 정책"; 전량 REVOKED는 차단의 필수
  조건이 아니었다). 보존 행의 물리 삭제는 #302 소유다. 영향 0행은 fresh 조회로 분류한다 — `WITHDRAWAL_PENDING`이면 멱등 202, 회원 없음은
  401 `-2001`. 탈퇴 회원 행은 절대 `ACTIVE`로 되돌리지 않으며 같은 provider의 다음 로그인은 released
  identity로 `findOrCreate` 신규 생성 경로(새 userId·새 subject)를 탄다 — 과거 콘텐츠·약관 동의와
  연결되지 않는다.

## Invariants

- provider account는 email이 아니라 `(provider, provider_user_id)`로 식별한다. `ACTIVE` 행의
  `provider_user_id`는 application invariant로 non-null이고, NULL은 탈퇴 행의 identity release뿐이다
  (nullable UNIQUE가 탈퇴 generation 다수를 허용하면서 신규 ACTIVE 행은 하나로 제한).
- 탈퇴 상태 전이·닉네임 갱신은 조건부 UPDATE 영향 행 수로만 판정한다 — 탈퇴 행을 되살릴 수 있는
  read-then-write entity 저장 경로를 만들지 않는다.
- `/a/api` 인증은 요청마다 회원 `ACTIVE`를 확인하되 #429부터 필터 경로만 공유 Redis 캐시를 탄다
  (ACTIVE=true만·탈퇴 시 DEL·TTL 안전망 — 한시적 stale의 범위는 "탈퇴 차단 정책"이 소유).
  token/refresh 발급은 매번 DB 직행으로 확인한다. 회원 없음과 탈퇴는 응답으로 구분되지 않으며,
  miss 경로의 DB 장애만 500으로 드러낸다(조용한 401 강등 금지 — Redis 장애는 miss 강등 후 DB 직행).
- access token에 PII를 넣거나 raw refresh/App Code를 저장하지 않는다.
- refresh rotation/reuse detection과 App Code one-time consumption의 atomicity를 보존한다.
- 401 응답·로그에 token 원문, Authorization 헤더, parse 실패 상세를 남기지 않는다.
- SecurityContext principal은 별도 래퍼 없는 `Long` userId다. 보호 operation의 principal parameter는
  원칙적으로 하나다 — 콘텐츠·push·앱 초기화/온보딩 controller는 `@CurrentSubject UUID subjectId`를,
  회원 account controller는 hidden `@AuthenticationPrincipal Long userId`를 받는다. 유일한 예외인 draft
  생성은 콘텐츠 owner subject와 계정 소유 약관 동의를 함께 판정하므로 두 hidden principal을 받되,
  콘텐츠 귀속에는 subjectId만, 동의 조회에는 userId만 쓴다(String principal을 만드는 테스트 헬퍼
  `user()` 사용 금지, `AuthTestSupport` 사용).
- access JWT의 `sub` claim은 raw userId다 — 콘텐츠 subject를 token·principal에 넣지 않으며,
  subject 전환은 인증 계약과 인증 도메인 스키마(users·refresh_tokens)를 바꾸지 않는다.

## Update When

Security chain, protected prefix, token claim/TTL/storage, provider validation, refresh behavior,
principal propagation 또는 fallback이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.auth.*' --tests 'com.laimory.server.config.*'
```
