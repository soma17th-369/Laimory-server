package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * worker 설정 불변식 — 경계 밖 수치를 기동 시점에 거절한다.
 */
class DailyReminderWorkerPropertiesTest {

    private static DailyReminderWorkerProperties properties(boolean enabled) {
        return new DailyReminderWorkerProperties(enabled, Duration.ofMinutes(30), 250, 1, 4,
                Duration.ofSeconds(30));
    }

    @Test
    void bootsWithDefaults() {
        assertThatCode(() -> properties(false)).doesNotThrowAnyException();
        assertThatCode(() -> properties(true)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveOrExcessiveLateness() {
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ZERO, 250, 1, 4,
                Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofHours(2), 250, 1, 4,
                Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOutOfRangeBatchAndConcurrency() {
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 0, 1, 4,
                Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 250, 3, 4,
                Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 250, 1, 0,
                Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 250, 1, 4,
                Duration.ofMinutes(10))).isInstanceOf(IllegalStateException.class);
    }
}
