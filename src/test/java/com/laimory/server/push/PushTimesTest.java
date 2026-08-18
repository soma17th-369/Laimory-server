package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 야간 광고 제한 구간(21:00 이상 또는 08:00 미만)의 경계와 KST 변환을 고정한다.
 * 이 판정이 어긋나면 야간 미동의 사용자에게 광고성 알림이 나간다.
 */
class PushTimesTest {

    @ParameterizedTest
    @CsvSource({
            "20:59, false",
            "21:00, true",   // 21:00 정각은 야간이다
            "23:59, true",
            "00:00, true",
            "07:59, true",
            "08:00, false",  // 08:00 정각은 주간이다
            "08:01, false",
    })
    void nightBoundariesAreInclusiveAtNineAndExclusiveAtEight(String time, boolean night) {
        assertThat(PushTimes.isNight(LocalTime.parse(time))).isEqualTo(night);
    }

    @Test
    void convertsInstantToSeoulWallClockRegardlessOfJvmZone() {
        // UTC 12:30 = KST 21:30 — JVM 기본 timezone이 무엇이든 같은 결과여야 한다.
        Instant instant = Instant.parse("2026-07-21T12:30:00Z");

        assertThat(PushTimes.kstWallClock(instant))
                .isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 30));
        assertThat(PushTimes.kstDate(instant)).isEqualTo(java.time.LocalDate.of(2026, 7, 21));
        assertThat(PushTimes.isNight(PushTimes.kstWallClock(instant))).isTrue();
    }

    @Test
    void kstDateRollsOverBeforeUtcMidnight() {
        // UTC 15:00 = KST 다음 날 00:00 — occurrence 날짜 계산이 UTC 날짜를 쓰면 하루가 어긋난다.
        assertThat(PushTimes.kstDate(Instant.parse("2026-07-21T15:00:00Z")))
                .isEqualTo(java.time.LocalDate.of(2026, 7, 22));
    }
}
