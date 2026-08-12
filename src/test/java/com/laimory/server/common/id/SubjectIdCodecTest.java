package com.laimory.server.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SubjectIdCodecTest {

    private static final SubjectId SUBJECT = SubjectId.fromBytes(
            HexFormat.of().parseHex("550e8400e29b41d4a716446655440000"));

    @Test
    void encodeDecode_roundTripsCanonicalUrlSafeUnpaddedValue() {
        String encoded = SubjectIdCodec.encode(SUBJECT);

        assertThat(encoded).hasSize(22).doesNotContain("=");
        assertThat(SubjectIdCodec.decode(encoded)).isEqualTo(SUBJECT);
    }

    @Test
    void decode_rejectsNullWrongLengthPaddingAndInvalidAlphabet() {
        String canonical = SubjectIdCodec.encode(SUBJECT);

        assertThatThrownBy(() -> SubjectIdCodec.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectIdCodec.decode(canonical + "="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectIdCodec.decode(canonical.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectIdCodec.decode("!" + canonical.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_rejectsCanonicalSixteenBytesThatAreNotUuidV4() {
        String versionZero = Base64.getUrlEncoder().withoutPadding().encodeToString(
                HexFormat.of().parseHex("0102030405060708090a0b0c0d0e0f10"));

        assertThatThrownBy(() -> SubjectIdCodec.decode(versionZero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid encoded subject id");
    }
}
