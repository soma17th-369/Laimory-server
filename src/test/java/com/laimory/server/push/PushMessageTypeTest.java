package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 알림 종류의 고정 문구 계약. 문구는 코드가 소유하며 DB·운영 설정으로 바꾸지 않는다. */
class PushMessageTypeTest {

    @ParameterizedTest
    @EnumSource(PushMessageType.class)
    void everyTypeHasFixedCopy(PushMessageType type) {
        assertThat(type.title()).isNotBlank();
        assertThat(type.body()).isNotBlank();
    }

    @Test
    void dailyReminderCopyIsFixed() {
        assertThat(PushMessageType.DAILY_REMINDER.title()).isEqualTo("타임라인을 완성해보세요!");
        assertThat(PushMessageType.DAILY_REMINDER.body()).isEqualTo("하루를 기록해보세요!");
    }

    @Test
    void scheduledTypeResolvesToItsMessage() {
        assertThat(ScheduledNotificationType.DAILY_REMINDER.pushMessageType())
                .isEqualTo(PushMessageType.DAILY_REMINDER);
    }
}
