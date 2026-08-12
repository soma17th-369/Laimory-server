package com.laimory.server.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * SubjectId 계약: canonical 16바이트 왕복, UUIDv4 생성, 잘못된 길이 거부, toString의 식별자 비노출.
 */
class SubjectIdTest {

    @Test
    void newRandom_producesUuidV4CanonicalBytes() {
        byte[] bytes = SubjectId.newRandom().bytes();

        assertThat(bytes).hasSize(16);
        assertThat((bytes[6] >> 4) & 0x0F).isEqualTo(4);      // version nibble = 4(random)
        assertThat(bytes[8] & 0xC0).isEqualTo(0x80);          // variant = 0b10
    }

    @Test
    void bytes_roundTripsThroughFromBytes() {
        SubjectId original = SubjectId.newRandom();

        SubjectId restored = SubjectId.fromBytes(original.bytes());

        assertThat(restored).isEqualTo(original);
        assertThat(restored.hashCode()).isEqualTo(original.hashCode());
        assertThat(restored.bytes()).isEqualTo(original.bytes());
    }

    @Test
    void fromBytes_knownVector_roundTripsExactly() {
        byte[] canonical = HexFormat.of().parseHex("550e8400e29b41d4a716446655440000");

        assertThat(SubjectId.fromBytes(canonical).bytes()).isEqualTo(canonical);
    }

    @Test
    void fromBytes_rejectsNonV4OrNonRfc4122Input() {
        byte[] versionZero = HexFormat.of().parseHex("0102030405060708090a0b0c0d0e0f10");
        byte[] nonRfcVariant = HexFormat.of().parseHex("550e8400e29b41d42716446655440000");

        assertThatThrownBy(() -> SubjectId.fromBytes(versionZero))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUIDv4");
        assertThatThrownBy(() -> SubjectId.fromBytes(nonRfcVariant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUIDv4");
    }

    @Test
    void fromBytes_rejectsNonSixteenByteInput() {
        assertThatThrownBy(() -> SubjectId.fromBytes(new byte[15]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SubjectId.fromBytes(new byte[17]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SubjectId.fromBytes(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bytes_returnsDefensiveCopy() {
        SubjectId subjectId = SubjectId.newRandom();
        byte[] first = subjectId.bytes();

        first[0] ^= (byte) 0xFF; // 반환 배열 훼손이 내부 상태를 바꾸면 안 된다

        assertThat(subjectId.bytes()).isNotEqualTo(first);
    }

    @Test
    void toString_doesNotExposeUuid() {
        SubjectId subjectId = SubjectId.newRandom();
        String hex = HexFormat.of().formatHex(subjectId.bytes());

        String rendered = subjectId.toString();

        // canonical hex 전체·UUID 하이픈 표기 모두 비노출 — 로그로 흘러도 식별자가 새지 않는다.
        assertThat(rendered).doesNotContain(hex.substring(0, 8));
        assertThat(rendered).doesNotContain("-");
    }
}
