package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.PushComplianceClass;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.push.service.NotificationConsentService.ConsentState;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 설정 orchestration 검증 — {@code HH:mm} 입력 계약, 광고성 알림 활성화·야간 시각의 동의 gate(부분 변경
 * 금지), 조회 응답 조립. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class PushSettingServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(31L);
    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;

    @Mock
    private PushPreferenceService pushPreferenceService;
    @Mock
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    @Mock
    private NotificationConsentService notificationConsentService;
    @Mock
    private TermDocumentService termDocumentService;

    private PushSettingService service() {
        return new PushSettingService(pushPreferenceService, scheduledNotificationPreferenceService,
                notificationConsentService, termDocumentService);
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

    private void givenConsent(boolean advertising, boolean night) {
        when(notificationConsentService.getOrCreateState(SUBJECT_ID))
                .thenReturn(new ConsentState(advertising, advertising ? 100L : null, night, night ? 200L : null));
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

        verify(scheduledNotificationPreferenceService, never()).getOrCreate(any(), any());
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

    // --- 광고성 알림 활성화 gate ---

    @Test
    void enableDailyReminder_withoutAdvertisingConsent_isRejectedWithoutPartialChange() {
        givenPreference(false, LocalTime.of(19, 0));
        givenConsent(false, false);

        assertThatThrownBy(() -> service().updateDailyReminderEnabled("v1", SUBJECT_ID, true))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.NOTIFICATION_CONSENT_REQUIRED));
        verify(scheduledNotificationPreferenceService, never()).updateEnabled(any(), any(), anyBoolean());
    }

    @Test
    void enableDailyReminder_nightTimeWithoutNightConsent_isRejected() {
        givenPreference(false, LocalTime.of(21, 0));
        givenConsent(true, false);

        assertThatThrownBy(() -> service().updateDailyReminderEnabled("v1", SUBJECT_ID, true))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.NOTIFICATION_CONSENT_REQUIRED));
        verify(scheduledNotificationPreferenceService, never()).updateEnabled(any(), any(), anyBoolean());
    }

    @Test
    void enableDailyReminder_withBothConsents_applies() {
        givenPreference(false, LocalTime.of(21, 0));
        givenConsent(true, true);

        service().updateDailyReminderEnabled("v1", SUBJECT_ID, true);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, true);
    }

    @Test
    void enableDailyReminder_dayTimeWithAdvertisingConsentOnly_applies() {
        givenPreference(false, LocalTime.of(20, 0));
        givenConsent(true, false);

        service().updateDailyReminderEnabled("v1", SUBJECT_ID, true);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, true);
    }

    @Test
    void disableDailyReminder_requiresNoConsent() {
        givenPreference(true, LocalTime.of(21, 0));

        service().updateDailyReminderEnabled("v1", SUBJECT_ID, false);

        verify(scheduledNotificationPreferenceService).updateEnabled(SUBJECT_ID, TYPE, false);
        // 끄는 경로는 동의 상태를 묻지 않는다(수신 거부는 언제나 가능해야 한다).
        verify(notificationConsentService, never()).getOrCreateState(any());
    }

    // --- 시각 변경 gate ---

    @Test
    void changeTimeToNight_whileEnabled_requiresNightConsent() {
        givenPreference(true, LocalTime.of(20, 0));
        givenConsent(true, false);

        assertThatThrownBy(() -> service().updateDailyReminderTime("v1", SUBJECT_ID, "21:30"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.NOTIFICATION_CONSENT_REQUIRED));
        verify(scheduledNotificationPreferenceService, never()).updateNotificationTime(any(), any(), any());
    }

    @Test
    void changeTimeToNight_whileDisabled_isStoredWithoutConsent() {
        // OFF면 저장만 하고 발송은 계속 차단된다 — 켜는 시점에 동의를 다시 요구한다.
        givenPreference(false, LocalTime.of(20, 0));

        service().updateDailyReminderTime("v1", SUBJECT_ID, "22:00");

        verify(scheduledNotificationPreferenceService)
                .updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(22, 0));
        verify(notificationConsentService, never()).getOrCreateState(any());
    }

    @Test
    void changeTimeToDay_afterConsentWithdrawn_isNotBlocked() {
        // 수신거부로 동의를 철회하면 enabled는 true로 남는다. 이때 시각을 주간으로 바꾸는 것까지 막으면
        // 리마인더를 끄거나 재동의하는 것 말고는 빠져나갈 수 없다 — 발송은 worker가 다시 막는다.
        givenPreference(true, LocalTime.of(21, 0));

        service().updateDailyReminderTime("v1", SUBJECT_ID, "20:00");

        verify(scheduledNotificationPreferenceService)
                .updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(20, 0));
        // 주간으로 옮기는 건 수신 범위를 넓히지 않으므로 동의를 아예 묻지 않는다.
        verify(notificationConsentService, never()).getOrCreateState(any());
    }

    @Test
    void changeTimeToDay_whileEnabled_needsNoNightConsent() {
        givenPreference(true, LocalTime.of(21, 0));

        service().updateDailyReminderTime("v1", SUBJECT_ID, "20:30");

        verify(scheduledNotificationPreferenceService)
                .updateNotificationTime(SUBJECT_ID, TYPE, LocalTime.of(20, 30));
        verify(notificationConsentService, never()).getOrCreateState(any());
    }

    // --- 조회 ---

    @Test
    void getSettings_returnsServerAuthoritativeStateWithConfirmedClassification() {
        when(pushPreferenceService.getOrCreatePushEnabled(SUBJECT_ID)).thenReturn(true);
        givenPreference(true, LocalTime.of(21, 0));
        givenConsent(true, false);
        when(notificationConsentService.findRecentEvents(any(), any())).thenReturn(List.of());
        when(termDocumentService.findVersionById(100L)).thenReturn(Optional.of("v1"));

        PushSettingsResponse response = service().getSettings("v1", SUBJECT_ID);

        assertThat(response.pushEnabled()).isTrue();
        assertThat(response.dailyReminder().enabled()).isTrue();
        assertThat(response.dailyReminder().time()).isEqualTo("21:00");
        // 분류는 서버가 확정한 값이라 항상 non-null이다 — 앱이 문구로 추정하지 않는다.
        assertThat(response.dailyReminder().classification()).isEqualTo(PushComplianceClass.ADVERTISING);
        assertThat(response.advertisingPushConsent().consented()).isTrue();
        assertThat(response.advertisingPushConsent().version()).isEqualTo("v1");
        assertThat(response.nightAdvertisingPushConsent().consented()).isFalse();
        assertThat(response.nightAdvertisingPushConsent().version()).isNull();
        assertThat(response.recentConsentResults()).isEmpty();
    }

    @Test
    void getSettings_missingDocumentForRecordedConsent_reportsNullVersionWithoutFailing() {
        when(pushPreferenceService.getOrCreatePushEnabled(SUBJECT_ID)).thenReturn(true);
        givenPreference(false, LocalTime.of(21, 0));
        givenConsent(true, false);
        when(notificationConsentService.findRecentEvents(any(), any())).thenReturn(List.of());
        when(termDocumentService.findVersionById(100L)).thenReturn(Optional.empty());

        PushSettingsResponse response = service().getSettings("v1", SUBJECT_ID);

        assertThat(response.advertisingPushConsent().consented()).isTrue();
        assertThat(response.advertisingPushConsent().version()).isNull();
    }
}
