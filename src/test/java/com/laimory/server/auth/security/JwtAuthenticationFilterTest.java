package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.auth.token.JwtTokens;
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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JWT 인증 필터 단위 검증: 유효 Bearer → Long principal(credentials 없음), 부재/형식 불량/무효 → 인증 없음
 * (사유 무관 통과 — 거절은 인가 단계 EntryPoint 몫), /a/api 경로 세그먼트에서만 동작. 인프라 0.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    private JwtTokens jwtTokens;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokens = new JwtTokens(SECRET, Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC));
        filter = new JwtAuthenticationFilter(jwtTokens);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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
    void validBearer_createsLongPrincipal_withoutRawTokenCredentials() throws Exception {
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
    void tamperedToken_leavesContextEmpty() throws Exception {
        String token = jwtTokens.issueAccessToken(42L);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(runFilter(request("Bearer " + tampered))).isNull();
    }

    @Test
    void expiredToken_leavesContextEmpty() throws Exception {
        String token = jwtTokens.issueAccessToken(42L);
        JwtTokens later = new JwtTokens(SECRET, Duration.ofMinutes(15),
                Clock.fixed(NOW.plus(Duration.ofMinutes(20)), ZoneOffset.UTC));
        JwtAuthenticationFilter laterFilter = new JwtAuthenticationFilter(later);

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
