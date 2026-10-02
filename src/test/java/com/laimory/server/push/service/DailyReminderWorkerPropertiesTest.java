package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * worker 설정 불변식 — 경계 밖 수치를 기동 시점에 거절한다.
 */
class DailyReminderWorkerPropertiesTest {

    private static DailyReminderWorkerProperties properties(boolean enabled) {
        return new DailyReminderWorkerProperties(enabled, 250, 1, 4);
    }

    @Test
    void bootsWithDefaults() {
        assertThatCode(() -> properties(false)).doesNotThrowAnyException();
        assertThatCode(() -> properties(true)).doesNotThrowAnyException();
    }

    @Test
    void rejectsOutOfRangeBatchAndConcurrency() {
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, 0, 1, 4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, 250, 3, 4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, 250, 1, 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
