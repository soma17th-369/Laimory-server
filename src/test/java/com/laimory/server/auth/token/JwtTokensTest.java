package com.laimory.server.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 자체 access token(HS256) 발급/검증 단위 검증: 왕복·만료(leeway 60s)·변조·서명 불일치·짧은 시크릿 fail-fast. 인프라 0. */
class JwtTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32바이트
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210"; // 다른 32바이트
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Instant NOW = Instant.parse("2026-07-07T00:00:00Z");
    private static final long USER_ID = 42L;

    private JwtTokens tokensAt(Instant instant) {
        return new JwtTokens(SECRET, ACCESS_TTL, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issueThenParse_recoversUserId() {
        JwtTokens tokens = tokensAt(NOW);
        String token = tokens.issueAccessToken(USER_ID);

        assertThat(tokens.parseUserId(token)).contains(USER_ID);
    }

    @Test
    void parse_pastLeeway_returnsEmpty() {
        String token = tokensAt(NOW).issueAccessToken(USER_ID);
        // 만료(15분) + leeway(60s)를 1초 넘긴 시점 → 무효.
        JwtTokens later = tokensAt(NOW.plus(ACCESS_TTL).plusSeconds(61));

        assertThat(later.parseUserId(token)).isEmpty();
    }

    @Test
    void parse_withinLeeway_returnsUserId() {
        String token = tokensAt(NOW).issueAccessToken(USER_ID);
        // 만료 후 30초 — leeway 60s 이내라 아직 유효.
        JwtTokens later = tokensAt(NOW.plus(ACCESS_TTL).plusSeconds(30));

        assertThat(later.parseUserId(token)).contains(USER_ID);
    }

    @Test
    void parse_tamperedToken_returnsEmpty() {
        JwtTokens tokens = tokensAt(NOW);
        String token = tokens.issueAccessToken(USER_ID);
        // 마지막 문자 몇 개를 교체해 서명을 깬다.
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(tokens.parseUserId(tampered)).isEmpty();
    }

    @Test
    void parse_garbageString_returnsEmpty() {
        assertThat(tokensAt(NOW).parseUserId("not-a-jwt")).isEmpty();
    }

    @Test
    void parse_differentSecret_returnsEmpty() {
        String token = tokensAt(NOW).issueAccessToken(USER_ID);
        JwtTokens other = new JwtTokens(OTHER_SECRET, ACCESS_TTL, Clock.fixed(NOW, ZoneOffset.UTC));

        Optional<Long> parsed = other.parseUserId(token);

        assertThat(parsed).isEmpty();
    }

    @Test
    void constructor_shortSecret_throwsIllegalState() {
        String shortSecret = "0123456789abcdef0123456789abcde"; // 31바이트

        assertThatThrownBy(() -> new JwtTokens(shortSecret, ACCESS_TTL, Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issue_zeroOrNegativeUserId_throwsIllegalState() {
        // MySQL AUTO_INCREMENT 계약(양수)에 어긋나는 발급은 내부 invariant 위반 — 과거 fallback 0 계열 접근 차단.
        JwtTokens tokens = tokensAt(NOW);

        assertThatThrownBy(() -> tokens.issueAccessToken(0L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tokens.issueAccessToken(-1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parse_zeroOrNegativeSubject_returnsEmpty_evenWithValidSignature() throws Exception {
        // 발급 경로가 막혀 있으므로 서명만 유효한 0·음수 sub 토큰을 직접 만들어 검증한다.
        JwtTokens tokens = tokensAt(NOW);

        assertThat(tokens.parseUserId(signedTokenWithSubject("0"))).isEmpty();
        assertThat(tokens.parseUserId(signedTokenWithSubject("-42"))).isEmpty();
    }

    /** 검증 대상과 같은 시크릿·클레임으로 서명하되 subject만 임의 지정한 토큰(우회 시나리오 재현용). */
    private String signedTokenWithSubject(String subject) throws Exception {
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer("laimory")
                .subject(subject)
                .issueTime(java.util.Date.from(NOW))
                .expirationTime(java.util.Date.from(NOW.plus(ACCESS_TTL)))
                .build();
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
