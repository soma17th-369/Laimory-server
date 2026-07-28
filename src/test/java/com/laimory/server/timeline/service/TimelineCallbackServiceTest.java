package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 콜백 오케스트레이터 단위 검증(단계별 토큰 chain 계약). 404·단계 토큰 검증·terminal 재전송 멱등/상충 거절·
 * 저장 없는 SUCCESS 거절·상태 전이·push best-effort. 인프라 0 — 결과 graph는 결과 저장 endpoint가 이미
 * 커밋했고 이 서비스는 Redis 전이만 기록한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineCallbackServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineAiResultReceiptService timelineAiResultReceiptService;
    @Mock
    private TimelineCompletionPushNotifier timelineCompletionPushNotifier;
    @Mock
    private TimelineMetrics timelineMetrics;
    @Mock
    private Timer.Sample callbackSample;

    @InjectMocks
    private TimelineCallbackService service;

    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final String TASK_ID = "t";
    private static final String INPUT_TOKEN = "raw-input-token";
    private static final String RESULT_TOKEN = TaskTokens.deriveResultToken(INPUT_TOKEN, TASK_ID);
    private static final String CALLBACK_TOKEN = TaskTokens.deriveCallbackToken(RESULT_TOKEN, TASK_ID);
    private static final TimelineDraftTask.TokenHashes TOKEN_HASHES = new TimelineDraftTask.TokenHashes(
            TaskTokens.hash(INPUT_TOKEN), TaskTokens.hash(RESULT_TOKEN), TaskTokens.hash(CALLBACK_TOKEN));

    @BeforeEach
    void setUpMetrics() {
        when(timelineMetrics.startCallback()).thenReturn(callbackSample);
    }

    private TimelineDraftTask processingTask() {
        // timelineWindow·processingStartedAt은 콜백 처리와 무관하다(PROCESSING 전용 부가 정보).
        return TimelineDraftTask.processing(USER_ID, RECORD_ID,
                new TimelineDraftTask.TimelineWindow(DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay()),
                TOKEN_HASHES, Instant.parse("2026-06-17T03:05:00Z"));
    }

    private void givenProcessingTask() {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));
    }

    private void givenStoredResult() {
        when(timelineAiResultReceiptService.exists(TASK_ID)).thenReturn(true);
    }

    private DraftTaskCallbackRequest successRequest() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
    }

    @Test
    void handleCallback_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("v1", "missing", CALLBACK_TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        verify(timelineMetrics).recordCallback(callbackSample);
    }

    @Test
    void handleCallback_withoutToken_throws401() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, null, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_withWrongToken_throws401() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_withInputStageToken_throws401() {
        // 입력 단계 토큰(T1)은 콜백을 인증하지 못한다 — 단계가 다르면 hash가 다르다.
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, INPUT_TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_successWithResultStageToken_throws401() {
        // 결과 저장 단계 토큰(T2)은 실패 보고에만 허용한다 — SUCCESS는 콜백 토큰(T3)이어야 한다.
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, RESULT_TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_failedWithResultStageToken_marksFailed() {
        // 실패는 결과 저장 단계를 거치지 않아 T3를 못 받는다 — T2로 보고할 수 있어야 한다.
        givenProcessingTask();

        service.handleCallback("v1", TASK_ID, RESULT_TOKEN,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "ai gave up"));

        verify(timelineTaskService).markFailed(
                TASK_ID, USER_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE, TOKEN_HASHES);
    }

    @Test
    void handleCallback_wrongTokenOnTerminalTask_throws401BeforeIdempotentReturn() {
        // token-first: 이미 SUCCESS(terminal)여도 토큰이 틀리면 멱등 단축 전에 401로 막힌다(해시 보존 덕분).
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASHES)));

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_sameTerminalResultReplayed_succeedsWithoutSideEffects() {
        // 응답 유실 후 재전송: 같은 결과면 그대로 200이며 상태·푸시를 다시 만들지 않는다.
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASHES)));

        service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN, successRequest());

        verifyNoStateChange();
    }

    @Test
    void handleCallback_conflictingTerminalResult_throws1017() {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(
                TimelineDraftTask.failed(USER_ID, RECORD_ID, -1008, TOKEN_HASHES)));

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_successWithoutStoredResult_throws1017() {
        // 저장 없는 SUCCESS 차단 — 파생 토큰 chain은 순서를 강제하지 못하므로 DB 영수증이 권위다.
        givenProcessingTask();
        when(timelineAiResultReceiptService.exists(TASK_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoStateChange();
    }

    @Test
    void handleCallback_aiReportedFailure_withoutCode_fallsBackTo1008() {
        givenProcessingTask();

        service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, null, "ai gave up"));

        InOrder order = inOrder(timelineTaskService, timelineCompletionPushNotifier);
        // errorCode 누락 -> -1008 폴백, 자유 텍스트는 저장하지 않는다.
        order.verify(timelineTaskService)
                .markFailed(TASK_ID, USER_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE, TOKEN_HASHES);
        // AI가 보고한 FAILED도 완료 푸시를 예약한다(실패도 조회 유도 신호).
        order.verify(timelineCompletionPushNotifier).notifyAsync(USER_ID, TASK_ID, TaskStatus.FAILED);
        // 실패는 결과 저장을 거치지 않으므로 영수증을 확인하지 않는다.
        verify(timelineAiResultReceiptService, never()).exists(anyString());
    }

    @Test
    void handleCallback_aiReportedFailure_withUnknownCode_fallsBackTo1008() {
        // 허용 목록 밖 코드(HTTP용 코드 포함)는 저장하지 않고 -1008로 폴백 — 오분류·유출 차단.
        givenProcessingTask();

        service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -9999, null));

        verify(timelineTaskService).markFailed(
                TASK_ID, USER_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE, TOKEN_HASHES);
    }

    @Test
    void handleCallback_success_marksSuccessThenPush() {
        givenProcessingTask();
        givenStoredResult();

        service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN, successRequest());

        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), any());

        // 불변식: 저장 확인 → terminal 저장 → 완료 푸시 예약.
        InOrder order = inOrder(timelineAiResultReceiptService, timelineTaskService,
                timelineCompletionPushNotifier);
        order.verify(timelineAiResultReceiptService).exists(TASK_ID);
        order.verify(timelineTaskService).markSuccess(TASK_ID, USER_ID, RECORD_ID, TOKEN_HASHES);
        order.verify(timelineCompletionPushNotifier).notifyAsync(USER_ID, TASK_ID, TaskStatus.SUCCESS);
    }

    @Test
    void handleCallback_invalidStatus_throwsBadRequest() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN,
                new DraftTaskCallbackRequest(TaskStatus.PROCESSING, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoStateChange();
    }

    @Test
    void handleCallback_terminalSaveFails_propagatesWithoutPush() {
        givenProcessingTask();
        givenStoredResult();
        doThrow(new RuntimeException("redis down")).when(timelineTaskService)
                .markSuccess(anyString(), anyLong(), anyLong(), any());

        assertThatThrownBy(() -> service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN, successRequest()))
                .isInstanceOf(RuntimeException.class);

        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }

    @Test
    void handleCallback_pushEnqueueFails_isSwallowedAndCallbackSucceeds() {
        // 알림은 best-effort: executor 제출 실패(포화·종료 중)도 콜백 200과 terminal 확정을 바꾸지 않는다.
        givenProcessingTask();
        givenStoredResult();
        doThrow(new RuntimeException("executor rejected")).when(timelineCompletionPushNotifier)
                .notifyAsync(anyLong(), anyString(), any());

        service.handleCallback("v1", TASK_ID, CALLBACK_TOKEN, successRequest());

        verify(timelineTaskService).markSuccess(TASK_ID, USER_ID, RECORD_ID, TOKEN_HASHES);
    }

    private void verifyNoStateChange() {
        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), any());
        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }
}
