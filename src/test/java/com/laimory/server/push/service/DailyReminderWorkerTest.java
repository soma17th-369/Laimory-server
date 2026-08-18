package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.PushSenderProperties;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * worker 검증 — 지연 허용 밖 occurrence의 무발송 skip, 비활성 시 no-op, claim/발송 실패 격리.
 * executor는 동기 실행으로 대체해 스케줄 배선이 아니라 판정 로직만 본다. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class DailyReminderWorkerTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(61L);
    /** KST 2026-07-21 21:10. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T12:10:00Z"), ZoneOffset.UTC);
    private static final PushSenderProperties SENDER =
            new PushSenderProperties("라이모리 주식회사", "help@laimory.app");

    @Mock
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    @Mock
    private DailyReminderPushNotifier dailyReminderPushNotifier;

    @Captor
    private ArgumentCaptor<List<ScheduledNotificationPreference>> deliverableCaptor;

    private static DailyReminderWorkerProperties properties(boolean enabled, Duration maxLateness) {
        return new DailyReminderWorkerProperties(enabled, maxLateness, 250, 1, 4,
                Duration.ofSeconds(30), SENDER);
    }

    private DailyReminderWorker worker(DailyReminderWorkerProperties properties) {
        return new DailyReminderWorker(scheduledNotificationPreferenceService, dailyReminderPushNotifier,
                properties, new SyncTaskExecutor(), CLOCK);
    }

    private static ScheduledNotificationPreference due(LocalDateTime nextDueAt) {
        ScheduledNotificationPreference preference = new ScheduledNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "id", new ScheduledNotificationPreferenceId(
                SUBJECT_ID, ScheduledNotificationType.DAILY_REMINDER));
        ReflectionTestUtils.setField(preference, "enabled", true);
        ReflectionTestUtils.setField(preference, "notificationTime", LocalTime.of(21, 0));
        ReflectionTestUtils.setField(preference, "nextDueAt", nextDueAt);
        return preference;
    }

    private void givenClaim(List<ScheduledNotificationPreference> first) {
        when(scheduledNotificationPreferenceService.claimDue(any(), anyInt()))
                .thenReturn(first)
                .thenReturn(List.of());
    }

    @Test
    void workerDisabled_doesNothing() {
        worker(properties(false, Duration.ofMinutes(30))).sendDueReminders();

        verify(scheduledNotificationPreferenceService, never()).claimDue(any(), anyInt());
    }

    @Test
    void deliversOccurrenceWithinLatenessAllowance() {
        // 21:00 예정 → 지금 21:10, 허용 30분 안이므로 발송한다.
        ScheduledNotificationPreference onTime = due(LocalDateTime.of(2026, 7, 21, 21, 0));
        givenClaim(List.of(onTime));

        worker(properties(true, Duration.ofMinutes(30))).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).containsExactly(onTime);
    }

    @Test
    void skipsOccurrenceBeyondLatenessAllowanceWithoutSending() {
        // 03:00 예정을 21:10에 복구 — 허용 지연을 넘겼으므로 발송 없이 넘긴다(새벽 알림 방지).
        givenClaim(List.of(due(LocalDateTime.of(2026, 7, 21, 3, 0))));

        worker(properties(true, Duration.ofMinutes(30))).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).isEmpty();
    }

    @Test
    void latenessBoundaryIsInclusive() {
        // 정확히 허용 지연만큼 늦은 occurrence는 아직 발송 대상이다.
        ScheduledNotificationPreference boundary = due(LocalDateTime.of(2026, 7, 21, 20, 40));
        givenClaim(List.of(boundary));

        worker(properties(true, Duration.ofMinutes(30))).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).containsExactly(boundary);
    }

    @Test
    void mixedBatch_deliversOnlyFreshOccurrences() {
        ScheduledNotificationPreference fresh = due(LocalDateTime.of(2026, 7, 21, 21, 0));
        ScheduledNotificationPreference stale = due(LocalDateTime.of(2026, 7, 21, 6, 0));
        givenClaim(List.of(fresh, stale));

        worker(properties(true, Duration.ofMinutes(30))).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).containsExactly(fresh);
    }

    @Test
    void claimFailure_isIsolatedAndStopsSlot() {
        when(scheduledNotificationPreferenceService.claimDue(any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> worker(properties(true, Duration.ofMinutes(30))).sendDueReminders())
                .doesNotThrowAnyException();
        verify(dailyReminderPushNotifier, never()).notifyAll(any());
    }

    @Test
    void sendFailure_isIsolated_andOccurrenceIsNotRetried() {
        // occurrence는 claim에서 이미 전진했으므로 이 batch는 유실된다(자동 재발송 없음).
        givenClaim(List.of(due(LocalDateTime.of(2026, 7, 21, 21, 0))));
        when(dailyReminderPushNotifier.notifyAll(any())).thenThrow(new RuntimeException("fcm down"));

        assertThatCode(() -> worker(properties(true, Duration.ofMinutes(30))).sendDueReminders())
                .doesNotThrowAnyException();
    }

    @Test
    void emptyClaim_stopsWithoutCallingNotifier() {
        when(scheduledNotificationPreferenceService.claimDue(any(), anyInt())).thenReturn(List.of());

        worker(properties(true, Duration.ofMinutes(30))).sendDueReminders();

        verify(dailyReminderPushNotifier, never()).notifyAll(any());
    }
}
