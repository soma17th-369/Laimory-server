package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * snapshot 불변식: 양의 version·32바이트 key·previous 쌍 동반·version 상이. 위반은 생성 시점
 * IllegalStateException이며 메시지는 항목 이름만 담는다(key 바이트 비노출).
 */
class SubjectHmacKeySnapshotTest {

    private static final byte[] CURRENT_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PREVIOUS_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);

    @Test
    void currentOnly_isValid() {
        SubjectHmacKeySnapshot snapshot = new SubjectHmacKeySnapshot((short) 1, CURRENT_KEY);

        assertThat(snapshot.currentVersion()).isEqualTo((short) 1);
        assertThat(snapshot.currentKey()).isEqualTo(CURRENT_KEY);
        assertThat(snapshot.hasPreviousKey()).isFalse();
        assertThat(snapshot.previousVersion()).isEmpty();
        assertThat(snapshot.previousKey()).isEmpty();
    }

    @Test
    void currentAndPrevious_isValid() {
        SubjectHmacKeySnapshot snapshot =
                new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, (short) 1, PREVIOUS_KEY);

        assertThat(snapshot.hasPreviousKey()).isTrue();
        assertThat(snapshot.previousVersion()).contains((short) 1);
        // Optional.contains는 배열 참조 동등이라 내용 비교로 단언한다.
        assertThat(snapshot.previousKey().orElseThrow()).isEqualTo(PREVIOUS_KEY);
    }

    @Test
    void rejectsNonPositiveCurrentVersion() {
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 0, CURRENT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentVersion");
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) -1, CURRENT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentVersion");
    }

    @Test
    void rejectsCurrentKeyNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 1, new byte[31]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentKey");
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 1, new byte[33]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentKey");
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currentKey");
    }

    @Test
    void rejectsHalfPresentPreviousPair() {
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, (short) 1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("together");
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, null, PREVIOUS_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("together");
    }

    @Test
    void rejectsNonPositiveOrDuplicatePreviousVersion() {
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, (short) 0, PREVIOUS_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previousVersion");
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, (short) 2, PREVIOUS_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void rejectsPreviousKeyNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, (short) 1, new byte[16]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previousKey");
    }

    @Test
    void rejectsSameCurrentAndPreviousKeyEvenWhenVersionsDiffer() {
        assertThatThrownBy(() ->
                new SubjectHmacKeySnapshot((short) 2, CURRENT_KEY, (short) 1, CURRENT_KEY.clone()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void validationFailure_doesNotLeakKeyBytes() {
        byte[] shortKey = "sensitive-key-material".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new SubjectHmacKeySnapshot((short) 1, shortKey))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("sensitive");
                    assertThat(e.getMessage()).doesNotContain(HexFormat.of().formatHex(shortKey));
                });
    }

    @Test
    void accessors_returnDefensiveCopies() {
        byte[] mutable = CURRENT_KEY.clone();
        SubjectHmacKeySnapshot snapshot = new SubjectHmacKeySnapshot((short) 1, mutable);

        mutable[0] ^= (byte) 0xFF;                 // 생성 후 원본 훼손
        byte[] exposed = snapshot.currentKey();
        exposed[1] ^= (byte) 0xFF;                 // 반환본 훼손

        assertThat(snapshot.currentKey()).isEqualTo(CURRENT_KEY);
    }

    @Test
    void toString_doesNotExposeKeyBytes() {
        SubjectHmacKeySnapshot snapshot = new SubjectHmacKeySnapshot((short) 1, CURRENT_KEY);

        assertThat(snapshot.toString()).doesNotContain("0123456789abcdef");
    }
}
