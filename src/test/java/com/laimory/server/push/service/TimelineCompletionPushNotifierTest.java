package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.push.PushMessageSender;
import com.laimory.server.push.PushMetrics;
import com.laimory.server.push.PushSendResult;
import com.laimory.server.timeline.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * 완료 푸시 notifier 단위 검증 — owner 전체 FID 발송, 0개 단축, snapshot 조건부 invalid 정리, 예외 최종 격리.
 * 인프라 0. ({@code @Async} 프록시 배선 자체는 {@code FirebasePushConfigTest}·콜백 회귀에서 다룬다 — 여기선
 * body 계약만.)
 */
@ExtendWith(MockitoExtension.class)
class TimelineCompletionPushNotifierTest {

    private static final long USER_ID = 7L;
    private static final String TASK_ID = "t-1";
    /** 고정 Clock — invalid 정리에 전달되는 snapshot 시각이 이 값이어야 한다. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-21T01:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime SNAPSHOT_AT = LocalDateTime.now(FIXED_CLOCK);

    @Mock
    private PushRegistrationService pushRegistrationService;
    @Mock
    private PushMessageSender pushMessageSender;
    @Mock
    private PushMetrics pushMetrics;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger notifierLogger;

    @BeforeEach
    void setUp() {
        notifierLogger = (Logger) LoggerFactory.getLogger(TimelineCompletionPushNotifier.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        notifierLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        notifierLogger.detachAppender(logAppender);
    }

    private TimelineCompletionPushNotifier notifier() {
        return new TimelineCompletionPushNotifier(
                pushRegistrationService, pushMessageSender, pushMetrics, FIXED_CLOCK);
    }

    @Test
    void notifyAsync_sendsToAllOwnerFids() {
        when(pushRegistrationService.findFirebaseInstallationIds(USER_ID))
                .thenReturn(List.of("fid-1", "fid-2"));
        when(pushMessageSender.send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1", "fid-2")))
                .thenReturn(new PushSendResult(2, 2, 0, List.of()));

        notifier().notifyAsync(USER_ID, TASK_ID, TaskStatus.SUCCESS);

        verify(pushMessageSender).send(TASK_ID, TaskStatus.SUCCESS, List.of("fid-1", "fid-2"));
        verify(pushMetrics).record(new PushSendResult(2, 2, 0, List.of()));
        // invalid 0건이면 정리 query를 만들지 않는다.
        verify(pushRegistrationService, never()).removeInvalidRegistrations(anyCollection(), any());
        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "timeline completion push result: taskId=t-1 taskStatus=SUCCESS "
                            + "targets=2 accepted=2 failed=0 invalidTargets=0");
        });
    }

    @Test
    void notifyAsync_noRegistrations_skipsSendEntirely() {
        when(pushRegistrationService.findFirebaseInstallationIds(USER_ID)).thenReturn(List.of());

        notifier().notifyAsync(USER_ID, TASK_ID, TaskStatus.FAILED);

        verify(pushMessageSender, never()).send(any(), any(), anyList());
        verify(pushMetrics, never()).record(any());
    }

    @Test
    void notifyAsync_removesOnlyInvalidFids_withSendSnapshotGuard() {
        when(pushRegistrationService.findFirebaseInstallationIds(USER_ID))
                .thenReturn(List.of("fid-1", "fid-2", "fid-3"));
        when(pushMessageSender.send(any(), any(), anyList()))
                .thenReturn(new PushSendResult(3, 1, 2, List.of("fid-2")));

        notifier().notifyAsync(USER_ID, TASK_ID, TaskStatus.SUCCESS);

        // snapshot(조회 시각)이 함께 전달돼 그 이후 재등록된 같은 FID 행은 삭제되지 않는다.
        verify(pushRegistrationService).removeInvalidRegistrations(List.of("fid-2"), SNAPSHOT_AT);
    }

    @Test
    void notifyAsync_registrationLookupFailure_isIsolated() {
        when(pushRegistrationService.findFirebaseInstallationIds(anyLong()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> notifier().notifyAsync(USER_ID, TASK_ID, TaskStatus.SUCCESS))
                .doesNotThrowAnyException();
        verify(pushMessageSender, never()).send(any(), any(), anyList());
        verify(pushMetrics, never()).record(any());
    }

    @Test
    void notifyAsync_sendFailure_isIsolatedAndSkipsCleanup() {
        when(pushRegistrationService.findFirebaseInstallationIds(USER_ID)).thenReturn(List.of("fid-1"));
        when(pushMessageSender.send(any(), any(), anyList())).thenThrow(new RuntimeException("fcm down"));

        assertThatCode(() -> notifier().notifyAsync(USER_ID, TASK_ID, TaskStatus.FAILED))
                .doesNotThrowAnyException();
        verify(pushRegistrationService, never()).removeInvalidRegistrations(anyCollection(), any());
        verify(pushMetrics, never()).record(any());
    }

    @Test
    void notifyAsync_cleanupFailure_isIsolated() {
        when(pushRegistrationService.findFirebaseInstallationIds(USER_ID))
                .thenReturn(List.of("fid-1", "fid-2"));
        when(pushMessageSender.send(any(), any(), anyList()))
                .thenReturn(new PushSendResult(2, 1, 1, List.of("fid-1")));
        doThrow(new RuntimeException("db down"))
                .when(pushRegistrationService).removeInvalidRegistrations(anyCollection(), any());

        assertThatCode(() -> notifier().notifyAsync(USER_ID, TASK_ID, TaskStatus.SUCCESS))
                .doesNotThrowAnyException();
        verify(pushMetrics).record(new PushSendResult(2, 1, 1, List.of("fid-1")));
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsSequence(
                        "timeline completion push result: taskId=t-1 taskStatus=SUCCESS "
                                + "targets=2 accepted=1 failed=1 invalidTargets=1",
                        "timeline completion push failed (polling이 안전망): taskId=t-1 status=SUCCESS");
    }
}
