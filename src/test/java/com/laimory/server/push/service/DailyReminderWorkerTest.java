package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.entity.DailyNotificationPreference;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalDateTime;
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
 * worker 검증 — claim한 occurrence는 예정 시각과 무관하게 발송, batch 수 상한만으로 run 종료,
 * 비활성 시 claim 없음, claim/발송 실패 격리.
 * executor는 동기 실행으로 대체해 스케줄 배선이 아니라 판정 로직만 본다. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class DailyReminderWorkerTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(61L);
    @Mock
    private DailyNotificationPreferenceService dailyNotificationPreferenceService;
    @Mock
    private DailyReminderPushNotifier dailyReminderPushNotifier;

    @Captor
    private ArgumentCaptor<List<DailyNotificationPreference>> deliverableCaptor;

    private static DailyReminderWorkerProperties properties(boolean enabled) {
        return new DailyReminderWorkerProperties(enabled, 250, 1, 4);
    }

    private DailyReminderWorker worker(DailyReminderWorkerProperties properties) {
        return new DailyReminderWorker(dailyNotificationPreferenceService, dailyReminderPushNotifier,
                properties, new SyncTaskExecutor());
    }

    private static DailyNotificationPreference due(LocalDateTime nextDueAt) {
        DailyNotificationPreference preference = new DailyNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(preference, "enabled", true);
        ReflectionTestUtils.setField(preference, "nextDueAt", nextDueAt);
        return preference;
    }

    private void givenClaim(List<DailyNotificationPreference> first) {
        when(dailyNotificationPreferenceService.claimDue(anyInt()))
                .thenReturn(first)
                .thenReturn(List.of());
    }

    @Test
    void workerDisabled_doesNothing() {
        worker(properties(false)).sendDueReminders();

        verify(dailyNotificationPreferenceService, never()).claimDue(anyInt());
    }

    @Test
    void deliversOnTimeOccurrence() {
        DailyNotificationPreference onTime = due(LocalDateTime.of(2026, 7, 21, 21, 0));
        givenClaim(List.of(onTime));

        worker(properties(true)).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).containsExactly(onTime);
    }

    @Test
    void deliversOverdueOccurrenceInsteadOfSkipping() {
        // 장애로 전날 21:00 run을 놓친 occurrence — 지연 필터가 없으므로 다음 run이 발송한다(#395).
        DailyNotificationPreference overdue = due(LocalDateTime.of(2026, 7, 20, 21, 0));
        givenClaim(List.of(overdue));

        worker(properties(true)).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).containsExactly(overdue);
    }

    @Test
    void mixedBatch_deliversOnTimeAndOverdueOccurrencesTogether() {
        DailyNotificationPreference onTime = due(LocalDateTime.of(2026, 7, 21, 21, 0));
        DailyNotificationPreference overdue = due(LocalDateTime.of(2026, 7, 21, 6, 0));
        givenClaim(List.of(onTime, overdue));

        worker(properties(true)).sendDueReminders();

        verify(dailyReminderPushNotifier).notifyAll(deliverableCaptor.capture());
        assertThat(deliverableCaptor.getValue()).containsExactly(onTime, overdue);
    }

    @Test
    void runEndsAtMaxBatchesPerRunWhileDueRemains() {
        // claim이 계속 due를 돌려줘도 batch 수 상한(2)에서 run이 끝난다 — 시간 상한은 없다.
        when(dailyNotificationPreferenceService.claimDue(anyInt()))
                .thenReturn(List.of(due(LocalDateTime.of(2026, 7, 21, 21, 0))));

        worker(new DailyReminderWorkerProperties(true, 250, 1, 2)).sendDueReminders();

        verify(dailyNotificationPreferenceService, times(2)).claimDue(250);
        verify(dailyReminderPushNotifier, times(2)).notifyAll(any());
    }

    @Test
    void maxBatchesPerRunIsSharedAcrossWorkerSlots() {
        // slot 2개가 상한 3을 나눠 쓴다 — slot마다 3이 아니라 run 전체가 3 batch다.
        when(dailyNotificationPreferenceService.claimDue(anyInt()))
                .thenReturn(List.of(due(LocalDateTime.of(2026, 7, 21, 21, 0))));

        worker(new DailyReminderWorkerProperties(true, 250, 2, 3)).sendDueReminders();

        verify(dailyNotificationPreferenceService, times(3)).claimDue(250);
        verify(dailyReminderPushNotifier, times(3)).notifyAll(any());
    }

    @Test
    void claimFailure_isIsolatedAndStopsSlot() {
        when(dailyNotificationPreferenceService.claimDue(anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> worker(properties(true)).sendDueReminders())
                .doesNotThrowAnyException();
        verify(dailyReminderPushNotifier, never()).notifyAll(any());
    }

    @Test
    void sendFailure_isIsolated_andOccurrenceIsNotRetried() {
        // occurrence는 claim에서 이미 전진했으므로 이 batch는 유실된다(자동 재발송 없음).
        givenClaim(List.of(due(LocalDateTime.of(2026, 7, 21, 21, 0))));
        when(dailyReminderPushNotifier.notifyAll(any())).thenThrow(new RuntimeException("fcm down"));

        assertThatCode(() -> worker(properties(true)).sendDueReminders())
                .doesNotThrowAnyException();
    }

    @Test
    void emptyClaim_stopsWithoutCallingNotifier() {
        when(dailyNotificationPreferenceService.claimDue(anyInt())).thenReturn(List.of());

        worker(properties(true)).sendDueReminders();

        verify(dailyReminderPushNotifier, never()).notifyAll(any());
    }
}
