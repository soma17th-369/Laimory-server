package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.push.PushSenderProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * worker 설정 불변식 — 광고성 알림을 켜면서 전송자 정보를 비워 두면 기동을 실패시킨다
 * (광고 표기 없는 광고성 발송 차단). 경계 밖 수치도 기동 시점에 거절한다.
 */
class DailyReminderWorkerPropertiesTest {

    private static final PushSenderProperties CONFIGURED =
            new PushSenderProperties("라이모리 주식회사", "help@laimory.app");
    private static final PushSenderProperties BLANK = new PushSenderProperties("", "");

    private static DailyReminderWorkerProperties properties(boolean enabled, PushSenderProperties sender) {
        return new DailyReminderWorkerProperties(enabled, Duration.ofMinutes(30), 250, 1, 4,
                Duration.ofSeconds(30), sender);
    }

    @Test
    void enablingAdvertisingWorkerWithoutSenderInfo_failsFast() {
        assertThatThrownBy(() -> properties(true, BLANK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.push.sender-name");
    }

    @Test
    void disabledWorker_bootsWithoutSenderInfo() {
        // 설정 API만 배포한 단계에서도 기동해야 한다.
        assertThatCode(() -> properties(false, BLANK)).doesNotThrowAnyException();
    }

    @Test
    void enabledWorkerWithSenderInfo_boots() {
        assertThatCode(() -> properties(true, CONFIGURED)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveOrExcessiveLateness() {
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ZERO, 250, 1, 4,
                Duration.ofSeconds(30), CONFIGURED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofHours(2), 250, 1, 4,
                Duration.ofSeconds(30), CONFIGURED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsOutOfRangeBatchAndConcurrency() {
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 0, 1, 4,
                Duration.ofSeconds(30), CONFIGURED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 250, 3, 4,
                Duration.ofSeconds(30), CONFIGURED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 250, 1, 0,
                Duration.ofSeconds(30), CONFIGURED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DailyReminderWorkerProperties(false, Duration.ofMinutes(30), 250, 1, 4,
                Duration.ofMinutes(10), CONFIGURED)).isInstanceOf(IllegalStateException.class);
    }
}
