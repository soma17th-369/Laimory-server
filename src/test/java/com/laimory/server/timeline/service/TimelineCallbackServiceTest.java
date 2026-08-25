package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/** 현재 task token과 Redis ProcessStage 기반 callback 전이를 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineCallbackServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineCompletionPushNotifier timelineCompletionPushNotifier;
    @Mock
    private TimelineMetrics timelineMetrics;
    @InjectMocks
    private TimelineCallbackService service;

    private static final String VERSION = "v1";
    private static final String TASK_ID = "task";
    private static final UUID SUBJECT_ID = id(7L);
    private static final long RECORD_ID = 42L;
    private static final String TASK_TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TASK_TOKEN);

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(SUBJECT_ID, RECORD_ID, null, TOKEN_HASH,
                        Instant.parse("2026-06-17T03:05:00Z"))
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private static DraftTaskCallbackRequest success() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
    }

    private static DraftTaskCallbackRequest failed() {
        return new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "inference failed");
    }

    @Test
    void successAtCallbackPending_marksTerminalAndPushes() {
        TimelineDraftTask task = taskAt(ProcessStage.CALLBACK_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        when(timelineTaskService.markSuccessIfPresent(TASK_ID, task)).thenReturn(true);

        service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, success());

        verify(timelineTaskService).markSuccessIfPresent(TASK_ID, task);
        verify(timelineCompletionPushNotifier).notifyAsync(SUBJECT_ID, TASK_ID, TaskStatus.SUCCESS);
    }

    @Test
    void successBeforeResultStored_returns409() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.RESULT_PENDING)));

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verify(timelineTaskService, never()).markSuccessIfPresent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedBeforeResult_marksTerminalAndPushes() {
        TimelineDraftTask task = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        when(timelineTaskService.markFailedIfPresent(
                TASK_ID, task, ExceptionType.AI_REPORTED_FAILURE)).thenReturn(true);

        service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, failed());

        verify(timelineTaskService).markFailedIfPresent(
                TASK_ID, task, ExceptionType.AI_REPORTED_FAILURE);
        verify(timelineCompletionPushNotifier).notifyAsync(SUBJECT_ID, TASK_ID, TaskStatus.FAILED);
    }

    @Test
    void failedCallback_neverLogsFreeTextErrorFromAi() {
        // AI의 자유 text error는 사용자 원문이 섞일 수 있다 — bounded numeric code 관측만 남긴다(#281).
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(TimelineCallbackService.class);
        appender.start();
        logger.addAppender(appender);
        try {
            String rawError = "RAW_AI_ERROR_TEXT_281_NEVER_LOG";
            TimelineDraftTask task = taskAt(ProcessStage.RESULT_PENDING);
            when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
            when(timelineTaskService.markFailedIfPresent(
                    TASK_ID, task, ExceptionType.AI_REPORTED_FAILURE)).thenReturn(true);

            service.handleCallback(VERSION, TASK_ID, TASK_TOKEN,
                    new DraftTaskCallbackRequest(TaskStatus.FAILED, 9999, rawError));

            assertThat(appender.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("ai reported failure")
                            .contains("code=" + ExceptionType.AI_REPORTED_FAILURE.code()));
            assertThat(appender.list).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(rawError));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void failedAfterResultStored_returns409() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.CALLBACK_PENDING)));

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, failed()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
    }

    @Test
    void wrongToken_returns401BeforeStateTransition() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.CALLBACK_PENDING)));

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, "wrong", success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(timelineCompletionPushNotifier);
    }

    @Test
    void sameTerminalCallbackReplay_isBodilessSuccess() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.success(SUBJECT_ID, RECORD_ID, TOKEN_HASH)));

        service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, success());

        verifyNoInteractions(timelineCompletionPushNotifier);
    }

    @Test
    void conflictingTerminalCallback_returns409() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.failed(
                        SUBJECT_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE.code(), TOKEN_HASH)));

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
    }

    @Test
    void failedWhileResultClaimed_returns409WithoutTerminalWrite() {
        // 선점은 token/stage를 바꾸지 않아 기존 검증만으로는 transaction 진행 중을 구분하지 못한다 —
        // 여기서 terminal을 확정하면 commit 회전의 SET XX가 terminal 위에 PROCESSING을 되쓴다.
        TimelineDraftTask claimed = taskAt(ProcessStage.RESULT_PENDING)
                .withRetryReceipt(new TimelineDraftTask.RetryReceipt(
                        TOKEN_HASH, Instant.parse("2026-06-17T03:10:00Z"),
                        Instant.parse("2026-06-17T03:10:15Z")));
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(claimed));

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, failed()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verify(timelineTaskService, never()).markFailedIfPresent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(timelineCompletionPushNotifier);
    }

    @Test
    void failedWithUnclaimedReceipt_isStillAccepted() {
        // 입력 회전이 남긴 receipt(claimedAt 없음)는 선점이 아니다 — FAILED는 그대로 허용된다.
        TimelineDraftTask issued = taskAt(ProcessStage.RESULT_PENDING)
                .withRetryReceipt(new TimelineDraftTask.RetryReceipt(
                        TOKEN_HASH, null, Instant.parse("2026-06-17T03:10:15Z")));
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(issued));
        when(timelineTaskService.markFailedIfPresent(
                TASK_ID, issued, ExceptionType.AI_REPORTED_FAILURE)).thenReturn(true);

        service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, failed());

        verify(timelineCompletionPushNotifier).notifyAsync(SUBJECT_ID, TASK_ID, TaskStatus.FAILED);
    }

    @Test
    void terminalWriteMissing_returns404WithoutPush() {
        // 검증 뒤 write 시점에 task가 만료된 경우 — XX=false는 부활 없이 404이고 push도 없다.
        TimelineDraftTask success = taskAt(ProcessStage.CALLBACK_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(success));
        when(timelineTaskService.markSuccessIfPresent(TASK_ID, success)).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));

        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.markFailedIfPresent(
                TASK_ID, pending, ExceptionType.AI_REPORTED_FAILURE)).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback(VERSION, TASK_ID, TASK_TOKEN, failed()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        verifyNoInteractions(timelineCompletionPushNotifier);
    }
}
