package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.RequestLogAttributes;
import com.laimory.server.user.service.UserAccountAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JWT 인증 필터 단위 검증: 유효 Bearer + ACTIVE 회원 → Long principal(credentials 없음), 부재/형식
 * 불량/무효 → 인증 없음(사유 무관 통과 — 거절은 인가 단계 EntryPoint 몫), 탈퇴·삭제 회원 → 인증 없음
 * (#305 — 인가 단계 401 -2001 수렴), 상태 조회 DB 장애 → fail-closed 500 -500 envelope + chain 중단,
 * /a/api 경로 세그먼트에서만 동작. 인프라 0.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JwtTokens jwtTokens;
    private UserAccountAccessService userAccountAccessService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokens = new JwtTokens(SECRET, Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC));
        userAccountAccessService = userId -> true;
        filter = newFilter(jwtTokens);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthenticationFilter newFilter(JwtTokens tokens) {
        // 실제 번들(messages*.properties)을 그대로 사용해 500 envelope 메시지 키 누락도 함께 잡는다.
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return new JwtAuthenticationFilter(tokens, userId -> userAccountAccessService.isActive(userId),
                new ApiErrorResponseWriter(messageSource, objectMapper));
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/t");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private Authentication runFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void validBearer_activeUser_createsLongPrincipal_withoutRawTokenCredentials() throws Exception {
        String token = jwtTokens.issueAccessToken(42L);

        Authentication authentication = runFilter(request("Bearer " + token));

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        // 컨트롤러 @AuthenticationPrincipal Long과 1:1 — principal은 래퍼 없는 Long이다.
        assertThat(authentication.getPrincipal()).isEqualTo(42L).isInstanceOf(Long.class);
        // token 원문은 credentials에 보존하지 않는다(유출면 최소화).
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    void validBearer_alsoExposesUserIdAsRequestAttributeForAccessLog() throws Exception {
        // access 완료 로그는 SecurityContext가 비워진 뒤 찍히므로 request attribute가 유일한 전달 경로다.
        MockHttpServletRequest request = request("Bearer " + jwtTokens.issueAccessToken(42L));

        runFilter(request);

        assertThat(request.getAttribute(RequestLogAttributes.USER_ID)).isEqualTo(42L);
    }

    @Test
    void validBearer_inactiveUser_leavesContextEmptyWithoutUserIdAttribute() throws Exception {
        // 탈퇴(WITHDRAWAL_PENDING)/삭제 회원: 서명이 유효해도 인증이 성립하지 않는다(#305) — 인가 단계
        // 401 -2001 수렴. userId 로그 attribute도 active 인증 성립 전이라 심지 않는다.
        userAccountAccessService = userId -> false;
        MockHttpServletRequest request = request("Bearer " + jwtTokens.issueAccessToken(42L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(RequestLogAttributes.USER_ID)).isNull();
        // 응답 작성 없이 chain을 계속 진행한다(401 작성은 인가 단계 EntryPoint 몫).
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void accountStatusLookupFailure_failsClosedWith500Envelope_withoutContinuingChain() throws Exception {
        // DB 장애를 조용한 401(credential 오류)로 숨기지 않는다 — 500 -500 envelope + ERROR 관측 후 중단.
        userAccountAccessService = userId -> {
            throw new RuntimeException("db down");
        };
        MockHttpServletRequest request = request("Bearer " + jwtTokens.issueAccessToken(42L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull(); // fail-closed — 컨트롤러에 도달하지 않는다
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(RequestLogAttributes.USER_ID)).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("header").path("code").asInt()).isEqualTo(-500);
        assertThat(body.path("body").isNull()).isTrue();
        // access 로그의 errorCode=-500·ERROR 레벨은 이 attribute에서 파생된다(EXCEPTION_TYPE 계약).
        assertThat(request.getAttribute(RequestLogAttributes.EXCEPTION_TYPE))
                .isEqualTo(ExceptionType.UNEXPECTED_ERROR);
    }

    @Test
    void unauthenticatedRequest_leavesUserIdAttributeUnset() throws Exception {
        MockHttpServletRequest missingHeader = request(null);
        MockHttpServletRequest tampered = request("Bearer " + tamper(jwtTokens.issueAccessToken(42L)));

        runFilter(missingHeader);
        runFilter(tampered);

        assertThat(missingHeader.getAttribute(RequestLogAttributes.USER_ID)).isNull();
        assertThat(tampered.getAttribute(RequestLogAttributes.USER_ID)).isNull();
    }

    @Test
    void bearerScheme_isCaseInsensitive() throws Exception {
        String token = jwtTokens.issueAccessToken(42L);

        Authentication authentication = runFilter(request("bearer " + token));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(42L);
    }

    @Test
    void missingHeader_leavesContextEmpty() throws Exception {
        assertThat(runFilter(request(null))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwdw==", "Bearer", "Bearer ", "Bearer    ", "Token abc"})
    void wrongSchemeOrBlankToken_leavesContextEmpty(String authorization) throws Exception {
        assertThat(runFilter(request(authorization))).isNull();
    }

    @Test
    void tamperedToken_leavesContextEmpty_withoutAccountLookup() throws Exception {
        // 서명 검증 실패 token으로는 상태 조회 자체를 하지 않는다(파싱 성공 후에만 active 검사).
        userAccountAccessService = userId -> {
            throw new AssertionError("account lookup must not run for an invalid token");
        };

        assertThat(runFilter(request("Bearer " + tamper(jwtTokens.issueAccessToken(42L))))).isNull();
    }

    private String tamper(String token) {
        return token.substring(0, token.length() - 4) + "AAAA";
    }

    @Test
    void expiredToken_leavesContextEmpty() throws Exception {
        String token = jwtTokens.issueAccessToken(42L);
        JwtTokens later = new JwtTokens(SECRET, Duration.ofMinutes(15),
                Clock.fixed(NOW.plus(Duration.ofMinutes(20)), ZoneOffset.UTC));
        JwtAuthenticationFilter laterFilter = newFilter(later);

        laterFilter.doFilter(request("Bearer " + token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "/a/api, false",
            "/a/api/v1/timeline/drafts, false",
            "/a/apiary, true",
            "/api/v1/intro, true",
            "/s/api/v1/timeline/drafts/t/callback, true",
            "/oauth2/authorization/google, true",
            "/login/oauth2/code/google, true",
    })
    void shouldNotFilter_matchesOnlyAuthenticatedPrefixSegments(String uri, boolean skipped) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);

        assertThat(filter.shouldNotFilter(request)).isEqualTo(skipped);
    }
}
