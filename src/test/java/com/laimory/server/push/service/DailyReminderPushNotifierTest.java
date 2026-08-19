package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.push.PushMessage;
import com.laimory.server.push.PushMessageSender;
import com.laimory.server.push.PushMessageType;
import com.laimory.server.push.PushMetrics;
import com.laimory.server.push.PushSendResult;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 일일 리마인더 발송 대상 선정 검증 — 전체 마스터 gate와 subject별 활성 FID 수집. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class DailyReminderPushNotifierTest {

    private static final UUID SUBJECT_A = TestSubjects.id(51L);
    private static final UUID SUBJECT_B = TestSubjects.id(52L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private PushPreferenceService pushPreferenceService;
    @Mock
    private PushRegistrationService pushRegistrationService;
    @Mock
    private PushMessageSender pushMessageSender;
    @Mock
    private PushMetrics pushMetrics;

    @Captor
    private ArgumentCaptor<List<String>> targetsCaptor;

    private DailyReminderPushNotifier notifier() {
        return new DailyReminderPushNotifier(pushPreferenceService, pushRegistrationService,
                pushMessageSender, pushMetrics, CLOCK);
    }

    private static ScheduledNotificationPreference claimed(UUID subjectId) {
        ScheduledNotificationPreference preference = new ScheduledNotificationPreference() {
        };
        ReflectionTestUtils.setField(preference, "id", new ScheduledNotificationPreferenceId(
                subjectId, ScheduledNotificationType.DAILY_REMINDER));
        ReflectionTestUtils.setField(preference, "enabled", true);
        ReflectionTestUtils.setField(preference, "notificationTime", LocalTime.of(21, 0));
        ReflectionTestUtils.setField(preference, "nextDueAt", LocalDateTime.of(2026, 7, 21, 21, 0));
        return preference;
    }

    @Test
    void sendsToActiveFidsOfSubjectsWithMasterEnabled() {
        when(pushPreferenceService.findPushEnabledBySubjectIds(any()))
                .thenReturn(Map.of(SUBJECT_A, true, SUBJECT_B, false));
        when(pushRegistrationService.findFirebaseInstallationIdsBySubjectIds(List.of(SUBJECT_A)))
                .thenReturn(List.of("fid-a1", "fid-a2"));
        when(pushMessageSender.send(any(), anyList()))
                .thenReturn(new PushSendResult(2, 2, 0, List.of()));

        DailyReminderPushNotifier.BatchOutcome outcome =
                notifier().notifyAll(List.of(claimed(SUBJECT_A), claimed(SUBJECT_B)));

        // 마스터 OFF인 B는 FID 조회 전에 빠진다. 설치가 여러 대면 각각 발송 대상이다.
        verify(pushMessageSender).send(any(), targetsCaptor.capture());
        assertThat(targetsCaptor.getValue()).containsExactly("fid-a1", "fid-a2");
        assertThat(outcome.deliverableSubjects()).isEqualTo(2);
        assertThat(outcome.eligibleSubjects()).isEqualTo(1);
        verify(pushMetrics).record(PushMessageType.DAILY_REMINDER, new PushSendResult(2, 2, 0, List.of()));
    }

    @Test
    void masterRowMissing_isExcludedInsteadOfAssumedEnabled() {
        // 예정 발송은 rollout 공백을 ON으로 추정하지 않는다.
        when(pushPreferenceService.findPushEnabledBySubjectIds(any())).thenReturn(Map.of());

        DailyReminderPushNotifier.BatchOutcome outcome = notifier().notifyAll(List.of(claimed(SUBJECT_A)));

        assertThat(outcome.eligibleSubjects()).isZero();
        verify(pushRegistrationService, never()).findFirebaseInstallationIdsBySubjectIds(any());
        verify(pushMessageSender, never()).send(any(), anyList());
    }

    @Test
    void usesDailyReminderMessageWithRoutingDataOnly() {
        when(pushPreferenceService.findPushEnabledBySubjectIds(any())).thenReturn(Map.of(SUBJECT_A, true));
        when(pushRegistrationService.findFirebaseInstallationIdsBySubjectIds(any()))
                .thenReturn(List.of("fid-a"));
        when(pushMessageSender.send(any(), anyList())).thenReturn(new PushSendResult(1, 1, 0, List.of()));

        notifier().notifyAll(List.of(claimed(SUBJECT_A)));

        ArgumentCaptor<PushMessage> messageCaptor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushMessageSender).send(messageCaptor.capture(), anyList());
        assertThat(messageCaptor.getValue().type()).isEqualTo(PushMessageType.DAILY_REMINDER);
        assertThat(messageCaptor.getValue().data()).containsOnlyKeys("route");
    }

    @Test
    void noTargets_skipsSendEntirely() {
        when(pushPreferenceService.findPushEnabledBySubjectIds(any())).thenReturn(Map.of(SUBJECT_A, true));
        when(pushRegistrationService.findFirebaseInstallationIdsBySubjectIds(any())).thenReturn(List.of());

        DailyReminderPushNotifier.BatchOutcome outcome = notifier().notifyAll(List.of(claimed(SUBJECT_A)));

        assertThat(outcome.targets()).isZero();
        verify(pushMessageSender, never()).send(any(), anyList());
    }

    @Test
    void invalidTargetsAreCleanedUpWithSnapshotGuard() {
        when(pushPreferenceService.findPushEnabledBySubjectIds(any())).thenReturn(Map.of(SUBJECT_A, true));
        when(pushRegistrationService.findFirebaseInstallationIdsBySubjectIds(any()))
                .thenReturn(List.of("fid-a"));
        when(pushMessageSender.send(any(), anyList()))
                .thenReturn(new PushSendResult(1, 0, 1, List.of("fid-a")));

        notifier().notifyAll(List.of(claimed(SUBJECT_A)));

        verify(pushRegistrationService).removeInvalidRegistrations(List.of("fid-a"), LocalDateTime.now(CLOCK));
    }

    @Test
    void emptyBatch_doesNothing() {
        assertThat(notifier().notifyAll(List.of()).targets()).isZero();

        verify(pushPreferenceService, never()).findPushEnabledBySubjectIds(anyCollection());
    }
}
