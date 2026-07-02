package com.laimory.server.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UuidV7 단위 검증(RFC 9562: version 7 / variant 2, 유일성, 내장 타임스탬프). 인프라 0. */
class UuidV7Test {

    @Test
    void randomUuidV7_hasVersion7AndVariant2() {
        UUID uuid = UuidV7.randomUuidV7();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void randomUuidV7_producesDistinctValues() {
        UUID a = UuidV7.randomUuidV7();
        UUID b = UuidV7.randomUuidV7();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void randomUuidV7_embedsCurrentTimestampInMostSignificant48Bits() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.randomUuidV7();
        long after = System.currentTimeMillis();

        long embeddedTs = uuid.getMostSignificantBits() >>> 16; // 상위 48비트 = unix_ts_ms

        // 호출 직전/직후 시각 범위 안(몇 초 여유)에 들어간다.
        assertThat(embeddedTs).isBetween(before - 2000, after + 2000);
    }

    @Test
    void randomUuidV7_timestampIsNonDecreasingAcrossConsecutiveCalls() {
        long prev = -1;
        for (int i = 0; i < 50; i++) {
            long ts = UuidV7.randomUuidV7().getMostSignificantBits() >>> 16;
            assertThat(ts).isGreaterThanOrEqualTo(prev);
            prev = ts;
        }
    }
}
