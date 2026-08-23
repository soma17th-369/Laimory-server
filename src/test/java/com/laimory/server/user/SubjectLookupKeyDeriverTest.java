package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * lookup key 파생 계약: HMAC-SHA-256(key, "content-subject-lookup:v1" || userId 8-byte BE).
 *
 * <p>기대값은 구현과 독립적으로 계산한 고정 벡터다(Python hmac/hashlib) — context 문자열·ASCII 인코딩·
 * 8-byte big-endian 결합 순서 중 하나라도 바뀌면 벡터가 깨진다. 이 값이 곧 저장된 mapping PK와의
 * 호환성 계약이므로, 벡터 갱신은 전체 mapping rotation을 의미한다.
 */
class SubjectLookupKeyDeriverTest {

    /** 결정적 테스트 key(32 ASCII bytes) — fixture 상수이며 보호 대상 secret이 아니다. */
    private static final byte[] TEST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TEST_PREVIOUS_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);

    private static final String VECTOR_USER_1 =
            "ea814b77acbe8449ffba3b84d2d4e633e4b20650ade1bffa29f61f3ca62ffe73";
    private static final String VECTOR_USER_42 =
            "ed7323ec89b405932ce2a9b5c9688e62e6b5406e2e9dd1f3b320f071a968bb26";
    private static final String VECTOR_PREVIOUS_USER_42 =
            "f683fbea13cebc72cce1099d0be680d73d90425e86fbd6146ebf209d77aa8513";

    private final SubjectLookupKeyDeriver deriver =
            new SubjectLookupKeyDeriver(new SubjectHmacKeySnapshot((short) 2, TEST_KEY));

    private final SubjectLookupKeyDeriver rotatingDeriver = new SubjectLookupKeyDeriver(
            new SubjectHmacKeySnapshot((short) 2, TEST_KEY, (short) 1, TEST_PREVIOUS_KEY));

    @Test
    void contextConstant_isPinned() {
        // context는 저장된 모든 mapping PK에 구워진 계약 — 상수 변경은 곧 전체 rotation이다.
        assertThat(SubjectLookupKeyDeriver.CONTEXT).isEqualTo("content-subject-lookup:v1");
    }

    @Test
    void deriveCurrent_matchesIndependentlyComputedVectors() {
        assertThat(deriver.deriveCurrent(1L))
                .isEqualTo(HexFormat.of().parseHex(VECTOR_USER_1));
        assertThat(deriver.deriveCurrent(42L))
                .isEqualTo(HexFormat.of().parseHex(VECTOR_USER_42));
    }

    @Test
    void deriveCurrent_isDeterministicPerUser_andDistinctAcrossUsers() {
        assertThat(deriver.deriveCurrent(42L)).isEqualTo(deriver.deriveCurrent(42L));
        assertThat(deriver.deriveCurrent(1L)).isNotEqualTo(deriver.deriveCurrent(2L));
        assertThat(deriver.deriveCurrent(1L)).hasSize(32);
    }

    @Test
    void derivePrevious_usesPreviousKey_producingDifferentLookupKey() {
        byte[] previous = rotatingDeriver.derivePrevious(42L).orElseThrow();

        assertThat(previous).isEqualTo(HexFormat.of().parseHex(VECTOR_PREVIOUS_USER_42));
        assertThat(previous).isNotEqualTo(rotatingDeriver.deriveCurrent(42L));
    }

    @Test
    void derivePrevious_isEmptyWithoutPreviousKey() {
        assertThat(deriver.derivePrevious(42L)).isEmpty();
    }

    @Test
    void currentVersion_reflectsSnapshot() {
        assertThat(deriver.currentVersion()).isEqualTo((short) 2);
    }

    @Test
    void rejectsNonPositiveUserId() {
        assertThatThrownBy(() -> deriver.deriveCurrent(0L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deriver.deriveCurrent(-7L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rotatingDeriver.derivePrevious(0L))
                .isInstanceOf(IllegalStateException.class);
    }
}
