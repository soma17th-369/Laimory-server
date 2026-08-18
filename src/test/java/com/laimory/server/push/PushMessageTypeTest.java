package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 알림 종류의 법적 분류·고정 문구 계약. 분류 누락은 정보성으로 흡수하지 않고 기동 실패로 드러낸다.
 */
class PushMessageTypeTest {

    @ParameterizedTest
    @EnumSource(PushMessageType.class)
    void everyTypeHasConfirmedClassificationAndFixedCopy(PushMessageType type) {
        assertThat(type.complianceClass()).isNotNull();
        assertThat(type.title()).isNotBlank();
        assertThat(type.body()).isNotBlank();
        // 광고 표기·수신거부 안내는 sender가 합성한다 — 원문 상수에 미리 박아 두지 않는다.
        assertThat(type.title()).doesNotContain("(광고)");
        assertThat(type.body()).doesNotContain("수신거부");
    }

    @Test
    void timelineCompletionIsInformational_dailyReminderIsAdvertising() {
        assertThat(PushMessageType.TIMELINE_COMPLETION.complianceClass())
                .isEqualTo(PushComplianceClass.INFORMATIONAL);
        assertThat(PushMessageType.TIMELINE_COMPLETION.isAdvertising()).isFalse();
        assertThat(PushMessageType.DAILY_REMINDER.complianceClass())
                .isEqualTo(PushComplianceClass.ADVERTISING);
        assertThat(PushMessageType.DAILY_REMINDER.isAdvertising()).isTrue();
    }

    @Test
    void dailyReminderCopyIsFixed() {
        assertThat(PushMessageType.DAILY_REMINDER.title()).isEqualTo("타임라인을 완성해보세요!");
        assertThat(PushMessageType.DAILY_REMINDER.body()).isEqualTo("하루를 기록해보세요!");
    }

    @Test
    void scheduledTypeInheritsClassificationFromItsMessage() {
        assertThat(ScheduledNotificationType.DAILY_REMINDER.pushMessageType())
                .isEqualTo(PushMessageType.DAILY_REMINDER);
        assertThat(ScheduledNotificationType.DAILY_REMINDER.complianceClass())
                .isEqualTo(PushComplianceClass.ADVERTISING);
    }

    @Test
    void startupValidatorAcceptsCurrentCatalog() {
        assertThatCode(PushComplianceStartupValidator::new).doesNotThrowAnyException();
    }
}
