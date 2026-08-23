package com.laimory.server.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 인증 토큰 원문 생성/해시/PKCE challenge 유틸 단위 검증: 랜덤성·문자셋·길이·상수시간 비교. 인프라 0. */
class AuthTokensTest {

    @Test
    void generate_producesDistinctBase64UrlTokensOfLength43() {
        String a = AuthTokens.generate();
        String b = AuthTokens.generate();

        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9_-]+");
        // 256-bit(32바이트) base64url no-padding = 43자.
        assertThat(a).hasSize(43);
        assertThat(b).hasSize(43);
    }

    @Test
    void sha256Hex_isDeterministic64CharHex() {
        String hash = AuthTokens.sha256Hex("some-token");

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(AuthTokens.sha256Hex("some-token")).isEqualTo(hash);
    }

    @Test
    void matchesChallenge_trueForVerifiersOwnChallenge() {
        String verifier = AuthTokens.generate();
        String challenge = AuthTokens.challenge(verifier);

        assertThat(AuthTokens.matchesChallenge(verifier, challenge)).isTrue();
    }

    @Test
    void matchesChallenge_falseForDifferentVerifier() {
        String verifier = AuthTokens.generate();
        String challenge = AuthTokens.challenge(verifier);

        assertThat(AuthTokens.matchesChallenge(AuthTokens.generate(), challenge)).isFalse();
    }

    @Test
    void matchesChallenge_falseForNullArguments() {
        String verifier = AuthTokens.generate();
        String challenge = AuthTokens.challenge(verifier);

        assertThat(AuthTokens.matchesChallenge(null, challenge)).isFalse();
        assertThat(AuthTokens.matchesChallenge(verifier, null)).isFalse();
    }
}
