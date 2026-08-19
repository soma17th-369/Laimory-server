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
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 설정 orchestration 검증 — {@code HH:mm} 입력 계약과 조회 응답 조립. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class PushSettingServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(31L);
    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;

    @Mock
    private PushPreferenceService pushPreferenceService;
    @Mock
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    private PushSettingService service() {
        return new PushSettingService(pushPreferenceService, scheduledNotificationPreferenceService);
    }

    private static ScheduledNotificationPreference preference(boolean enabled, LocalTime time) {
        ScheduledNotificationPreference preference = new ScheduledNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "id", new ScheduledNotificationPreferenceId(SUBJECT_ID, TYPE));
        ReflectionTestUtils.setField(preference, "enabled", enabled);
        ReflectionTestUtils.setField(preference, "notificationTime", time);
        ReflectionTestUtils.setField(preference, "nextDueAt", LocalDateTime.of(2026, 7, 21, 21, 0));
        ReflectionTestUtils.setField(preference, "lastProcessedOccurrenceDate", LocalDate.of(2026, 7, 20));
        return preference;
    }

    private void givenPreference(boolean enabled, LocalTime time) {
        when(scheduledNotificationPreferenceService.getOrCreate(SUBJECT_ID, TYPE))
                .thenReturn(preference(enabled, time));
    }

    // --- 시각 입력 계약 ---

    @ParameterizedTest
    @ValueSource(strings = {"21:00", "00:00", "23:59", "07:30"})
    void acceptsMinuteGranularityWallClock(String time) {
        assertThat(PushSettingService.parseTime(time)).isEqualTo(LocalTime.parse(time));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9:05", "21:00:00", "21시", "24:00", "21:60", "", " "})
    void rejectsMalformedTimeBeforeAnyWrite(String time) {
        assertThatThrownBy(() -> PushSettingService.parseTime(time))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateTime_malformedInput_neverTouchesPreferences() {
        assertThatThrownBy(() -> service().updateDailyReminderTime("v1", SUBJECT_ID, "9시"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(scheduledNotificationPreferenceService, never()).updateNotificationTime(any(), any(), any());
    }

    // --- 마스터 ---

    @Test
    void updatePushEnabled_delegatesToMasterOnly() {
        service().updatePushEnabled("v1", SUBJECT_ID, false);

        verify(pushPreferenceService).updatePushEnabled(SUBJECT_ID, false);
        verify(scheduledNotificationPreferenceService, never()).updateEnabled(any(), any(), anyBoolean());
    }

    @Test
    void updatePushEnabled_nullBody_isRejected() {
        assertThatThrownBy(() -> service().updatePushEnabled("v1", SUBJECT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(pushPreferenceService, never()).updatePushEnabled(any(), anyBoolean());
    }

    // --- 리마인더 토글·시각 ---

    @Test
    void enableDailyReminder_delegatesWithoutConsultingAnythingElse() {
        service().updateDailyReminderEnabled("v1", SUBJECT_ID, true);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, true);
    }

    @Test
    void disableDailyReminder_delegates() {
        service().updateDailyReminderEnabled("v1", SUBJECT_ID, false);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, false);
    }

    @Test
    void updateTime_storesParsedWallClock() {
        service().updateDailyReminderTime("v1", SUBJECT_ID, "22:00");

        verify(scheduledNotificationPreferenceService)
                .updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(22, 0));
    }

    @Test
    void updateTime_nightValueIsStoredLikeAnyOther() {
        // 정보성 알림이라 야간 시각에 별도 조건이 없다.
        service().updateDailyReminderTime("v1", SUBJECT_ID, "23:30");

        verify(scheduledNotificationPreferenceService)
                .updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(23, 30));
    }

    // --- 조회 ---

    @Test
    void getSettings_returnsServerAuthoritativeStateWithConfirmedClassification() {
        when(pushPreferenceService.getOrCreatePushEnabled(SUBJECT_ID)).thenReturn(true);
        givenPreference(true, LocalTime.of(21, 0));

        PushSettingsResponse response = service().getSettings("v1", SUBJECT_ID);

        assertThat(response.pushEnabled()).isTrue();
        assertThat(response.dailyReminder().enabled()).isTrue();
        assertThat(response.dailyReminder().time()).isEqualTo("21:00");
    }

}
