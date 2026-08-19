package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * KST 벽시계 변환을 고정한다 — 예정 시각 계산과 worker claim이 JVM 기본 timezone에 흔들리지 않아야 한다.
 */
class PushTimesTest {

    @Test
    void convertsInstantToSeoulWallClockRegardlessOfJvmZone() {
        // UTC 12:30 = KST 21:30 — JVM 기본 timezone이 무엇이든 같은 결과여야 한다.
        Instant instant = Instant.parse("2026-07-21T12:30:00Z");

        assertThat(PushTimes.kstWallClock(instant))
                .isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 30));
        assertThat(PushTimes.kstDate(instant)).isEqualTo(java.time.LocalDate.of(2026, 7, 21));
    }

    @Test
    void kstDateRollsOverBeforeUtcMidnight() {
        // UTC 15:00 = KST 다음 날 00:00 — occurrence 날짜 계산이 UTC 날짜를 쓰면 하루가 어긋난다.
        assertThat(PushTimes.kstDate(Instant.parse("2026-07-21T15:00:00Z")))
                .isEqualTo(java.time.LocalDate.of(2026, 7, 22));
    }
}
