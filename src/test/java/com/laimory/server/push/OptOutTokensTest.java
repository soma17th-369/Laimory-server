package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 수신거부 credential의 형식 계약과 hash 비교. 원문은 저장하지 않으므로 형식 검증이 곧 입력 방어선이다.
 */
class OptOutTokensTest {

    private static String token(byte fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Test
    void hashesValidBase64UrlTokenToSha256Hex() {
        String value = token((byte) 7);
        assertThat(value).hasSize(43);

        String hash = OptOutTokens.hash(value);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        // 같은 원문은 항상 같은 hash(저장값 비교의 전제).
        assertThat(OptOutTokens.hash(value)).isEqualTo(hash);
        // 원문이 hash에 그대로 드러나지 않는다.
        assertThat(hash).doesNotContain(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "too-short",
            "AAAA+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // base64url 아님(+)
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",  // 44자(32바이트 아님)
    })
    void rejectsMalformedTokensWithoutEchoingValue(String value) {
        assertThatThrownBy(() -> OptOutTokens.hash(value))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> {
                    if (value != null && !value.isEmpty()) {
                        assertThat(e.getMessage()).doesNotContain(value);
                    }
                });
    }

    @Test
    void matchesOnlyExactToken() {
        String value = token((byte) 1);
        String stored = OptOutTokens.hash(value);

        assertThat(OptOutTokens.matches(stored, value)).isTrue();
        assertThat(OptOutTokens.matches(stored, token((byte) 2))).isFalse();
    }

    @Test
    void neverMatchesWhenRegistrationHasNoStoredHash() {
        // 수신거부 수단이 없는 legacy 설치 — 어떤 token도 통과시키지 않는다.
        assertThat(OptOutTokens.matches(null, token((byte) 1))).isFalse();
        assertThat(OptOutTokens.matches("", token((byte) 1))).isFalse();
    }

    @Test
    void malformedCandidateIsRejectedWithoutThrowing() {
        // 검증 경로는 형식 오류도 "불일치"로 수렴시킨다 — 오류 종류가 응답으로 새지 않게 한다.
        assertThat(OptOutTokens.matches(OptOutTokens.hash(token((byte) 1)), "not-a-token")).isFalse();
        assertThat(OptOutTokens.matches(OptOutTokens.hash(token((byte) 1)), null)).isFalse();
    }
}
