package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Callback-Token 발급·해시·상수시간 비교 유틸 단위 검증. 인프라 0. */
class CallbackTokensTest {

    @Test
    void generate_producesDistinctUrlSafeTokens() {
        String a = CallbackTokens.generate();
        String b = CallbackTokens.generate();

        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9_-]+");      // URL-safe, padding 없음
        assertThat(a.length()).isGreaterThanOrEqualTo(43); // 32바이트(256-bit) base64url ≈ 43자
    }

    @Test
    void hash_isDeterministic_andDiffersFromToken() {
        String token = CallbackTokens.generate();

        assertThat(CallbackTokens.hash(token)).isEqualTo(CallbackTokens.hash(token));
        assertThat(CallbackTokens.hash(token)).isNotEqualTo(token);
    }

    @Test
    void matches_trueForCorrect_falseForWrongOrNull() {
        String token = CallbackTokens.generate();
        String hash = CallbackTokens.hash(token);

        assertThat(CallbackTokens.matches(token, hash)).isTrue();
        assertThat(CallbackTokens.matches("other-token", hash)).isFalse();
        assertThat(CallbackTokens.matches(null, hash)).isFalse();
        assertThat(CallbackTokens.matches(token, null)).isFalse();
    }
}
