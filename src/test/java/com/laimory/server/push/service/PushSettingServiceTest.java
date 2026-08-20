package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 설정 orchestration 검증 — ON/OFF 위임과 조회 응답 조립. 인프라 0.
 *
 * <p>시각은 서버 고정이라 입력 계약이 없다(#318) — 조회 응답의 {@code HH:mm} 표기만 남는다.
 */
@ExtendWith(MockitoExtension.class)
class PushSettingServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(31L);
    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;

    @Mock
    private SubjectPreferenceService subjectPreferenceService;
    @Mock
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    private PushSettingService service() {
        return new PushSettingService(subjectPreferenceService, scheduledNotificationPreferenceService);
    }

    private void givenSettings(boolean enabled, LocalTime time) {
        when(scheduledNotificationPreferenceService.findSettings(SUBJECT_ID, TYPE))
                .thenReturn(new ScheduledNotificationPreferenceService.Settings(enabled, time));
    }

    // --- 마스터 ---

    @Test
    void updatePushEnabled_delegatesToMasterOnly() {
        service().updatePushEnabled("v1", SUBJECT_ID, false);

        verify(subjectPreferenceService).updatePushEnabled(SUBJECT_ID, false);
        verify(scheduledNotificationPreferenceService, never()).updateEnabled(any(), any(), anyBoolean());
    }

    @Test
    void updatePushEnabled_nullBody_isRejected() {
        assertThatThrownBy(() -> service().updatePushEnabled("v1", SUBJECT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(subjectPreferenceService, never()).updatePushEnabled(any(), anyBoolean());
    }

    // --- 리마인더 토글 ---

    @Test
    void enableDailyReminder_delegatesWithoutReadingFirst() {
        service().updateDailyReminderEnabled("v1", SUBJECT_ID, true);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, true);
        // 쓰기 경로는 값을 읽지 않는다 — 읽고 계산하면 그 사이에 다른 변경이 끼어들 수 있다.
        verify(scheduledNotificationPreferenceService, never()).findSettings(any(), any());
    }

    @Test
    void disableDailyReminder_delegates() {
        // 기본이 ON이라 이 경로가 사용자가 수신을 멈추는 유일한 수단이다.
        service().updateDailyReminderEnabled("v1", SUBJECT_ID, false);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, false);
    }

    @Test
    void updateDailyReminderEnabled_nullBody_isRejected() {
        assertThatThrownBy(() -> service().updateDailyReminderEnabled("v1", SUBJECT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(scheduledNotificationPreferenceService, never()).updateEnabled(any(), any(), anyBoolean());
    }

    // --- 조회 ---

    @Test
    void getSettings_returnsServerAuthoritativeStateWithConfirmedClassification() {
        when(subjectPreferenceService.findPushEnabled(SUBJECT_ID)).thenReturn(true);
        givenSettings(true, LocalTime.of(21, 0));

        PushSettingsResponse response = service().getSettings("v1", SUBJECT_ID);

        assertThat(response.pushEnabled()).isTrue();
        assertThat(response.dailyReminder().enabled()).isTrue();
        assertThat(response.dailyReminder().time()).isEqualTo("21:00");
    }

}
