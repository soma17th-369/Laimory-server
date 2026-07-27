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
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.TaskStatus;
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
 * 콜백 오케스트레이터 단위 검증(direct-write 계약). 404·token 검증/원자 소비·terminal 재사용 거절·
 * 상태 전이·push best-effort. 인프라 0,
 * finalize 경로 없음 — AI가 commit을 이미 마친 상태라 서버는 Redis 전이만 기록한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineCallbackServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
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
    private static final String TOKEN = "raw-callback-token";
    private static final String TOKEN_HASH = CallbackTokens.hash(TOKEN);

    @BeforeEach
    void setUpMetrics() {
        when(timelineMetrics.startCallback()).thenReturn(callbackSample);
    }

    private TimelineDraftTask processingTask() {
        // timelineWindow·processingStartedAt은 콜백 처리와 무관하다(PROCESSING 전용 부가 정보).
        return TimelineDraftTask.processing(USER_ID, RECORD_ID,
                new TimelineDraftTask.TimelineWindow(DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay()),
                TOKEN_HASH, Instant.parse("2026-06-17T03:05:00Z"));
    }

    private void givenProcessingTask() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
    }

    private void givenConsumableProcessingTask() {
        givenProcessingTask();
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(true);
    }

    private DraftTaskCallbackRequest successRequest() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
    }

    @Test
    void handleCallback_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("v1", "missing", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        verify(timelineTaskService, never()).consumeCallbackToken(anyString());
        verify(timelineMetrics).recordCallback(callbackSample);
    }

    @Test
    void handleCallback_withoutCallbackToken_throws401() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", "t", null, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verify(timelineTaskService, never()).consumeCallbackToken(anyString());
        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void handleCallback_withWrongCallbackToken_throws401() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verify(timelineTaskService, never()).consumeCallbackToken(anyString());
        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void handleCallback_tokenCheckedBeforeIdempotentReturn_wrongTokenOnTerminalTask_throws401() {
        // token-first: 이미 SUCCESS(terminal)여도 토큰이 틀리면 멱등 단축 전에 401로 막힌다(해시 보존 덕분).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASH)));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verify(timelineTaskService, never()).consumeCallbackToken(anyString());
    }

    @Test
    void handleCallback_terminalTaskWithoutConsumptionMarker_consumesThenRejects1012() {
        // callback 외부 경로에서 종결된 terminal task는 marker가 없을 수 있다.
        // token을 먼저 소비한 뒤 terminal 안전망에서 거절한다.
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASH)));
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(true);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1012));

        verify(timelineTaskService).consumeCallbackToken("t");
        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), anyString());
        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }

    @Test
    void handleCallback_consumedTerminalTask_rejects1012BeforeSideEffects() {
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(USER_ID, RECORD_ID,
                        -1008, TOKEN_HASH)));
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1012));

        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), anyString());
        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }

    @Test
    void handleCallback_processingTaskWithConsumedToken_rejects1012BeforeStateChange() {
        givenProcessingTask();
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(false);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1012));

        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), anyString());
        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }

    @Test
    void handleCallback_aiReportedFailure_marksFailed() {
        givenConsumableProcessingTask();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, null, "ai gave up");

        service.handleCallback("v1", "t", TOKEN, req);

        InOrder order = inOrder(timelineTaskService, timelineCompletionPushNotifier);
        order.verify(timelineTaskService).consumeCallbackToken("t");
        // errorCode 누락 -> 1008 폴백, 자유 텍스트는 저장 안 함.
        order.verify(timelineTaskService)
                .markFailed("t", USER_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE, TOKEN_HASH);
        // AI가 보고한 FAILED도 완료 푸시를 예약한다(실패도 조회 유도 신호).
        order.verify(timelineCompletionPushNotifier).notifyAsync(USER_ID, "t", TaskStatus.FAILED);
    }

    @Test
    void handleCallback_aiReportedFailure_withValidCode_storesIt() {
        givenConsumableProcessingTask();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "gpu timeout");

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed(
                "t", USER_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE, TOKEN_HASH);
    }

    @Test
    void handleCallback_aiReportedFailure_withUnknownCode_fallsBackTo1008() {
        // 허용 목록 밖 코드(HTTP용 코드 포함)는 저장하지 않고 ERROR_1008로 폴백 — 오분류·유출 차단.
        givenConsumableProcessingTask();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, -9999, null);

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed(
                "t", USER_ID, RECORD_ID, ExceptionType.AI_REPORTED_FAILURE, TOKEN_HASH);
    }

    @Test
    void handleCallback_success_marksSuccessThenPush() {
        givenConsumableProcessingTask();

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markSuccess("t", USER_ID, RECORD_ID, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), anyString());

        // 불변식: token 소비 → terminal 저장 → 완료 푸시 예약.
        InOrder order = inOrder(timelineTaskService, timelineCompletionPushNotifier);
        order.verify(timelineTaskService).consumeCallbackToken("t");
        order.verify(timelineTaskService).markSuccess("t", USER_ID, RECORD_ID, TOKEN_HASH);
        order.verify(timelineCompletionPushNotifier).notifyAsync(USER_ID, "t", TaskStatus.SUCCESS);
    }

    @Test
    void handleCallback_invalidStatus_throwsBadRequest() {
        givenConsumableProcessingTask();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.PROCESSING, null, null);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, req))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }

    @Test
    void handleCallback_terminalSaveFails_keepsTokenConsumedAndRejectsRetry() {
        // terminal 저장은 실패했지만 인증 시점에 소비한 token marker는 환불하지 않는다.
        givenProcessingTask();
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(true, false);
        doThrow(new RuntimeException("redis down")).when(timelineTaskService)
                .markSuccess(anyString(), anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOf(RuntimeException.class);

        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1012));
    }

    @Test
    void handleCallback_tokenConsumeFails_doesNotChangeState() {
        givenProcessingTask();
        doThrow(new RuntimeException("redis down")).when(timelineTaskService).consumeCallbackToken("t");

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOf(RuntimeException.class);

        verify(timelineTaskService, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), anyString());
        verify(timelineCompletionPushNotifier, never()).notifyAsync(anyLong(), anyString(), any());
    }

    @Test
    void handleCallback_pushEnqueueFails_isSwallowedAndCallbackSucceeds() {
        // 알림은 best-effort: executor 제출 실패(포화·종료 중)도 콜백 200과 terminal 확정을 바꾸지 않는다.
        givenConsumableProcessingTask();
        doThrow(new RuntimeException("executor rejected")).when(timelineCompletionPushNotifier)
                .notifyAsync(anyLong(), anyString(), any());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markSuccess("t", USER_ID, RECORD_ID, TOKEN_HASH);
    }
}
