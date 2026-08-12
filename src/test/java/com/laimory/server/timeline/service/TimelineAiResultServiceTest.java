package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.common.privacy.RedactionResult;
import com.laimory.server.common.privacy.RedactionType;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** RESULT → CALLBACK token/stage 선점과 DB 실패 rollback을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineAiResultServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineAiResultTransactionService timelineAiResultTransactionService;
    // 실물 redactor를 spy로 주입한다 — 치환 검증은 실물 동작으로, 실패 주입만 stubbing으로 한다.
    @Spy
    private PrivacyRedactor privacyRedactor = new PrivacyRedactor();
    @InjectMocks
    private TimelineAiResultService service;

    private static final String VERSION = "v1";
    private static final String TASK_ID = "t";
    private static final UUID SUBJECT_ID = id(7L);
    private static final long RECORD_ID = 42L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String TASK_TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TASK_TOKEN);

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(SUBJECT_ID, RECORD_ID, null, TOKEN_HASH,
                        Instant.parse("2026-06-17T03:05:00Z"))
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private AiTimelineResultRequest result() {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, "점심에 누구와 함께였나요?",
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_rotatesToCallbackToken_thenStores() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);

        AiTimelineResultResponse response = service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        ArgumentCaptor<TimelineDraftTask> claimed = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), eq(pending), claimed.capture());
        assertThat(claimed.getValue().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
        assertThat(claimed.getValue().matchesToken(response.taskToken())).isTrue();
        assertThat(response.taskToken()).matches("[A-Za-z0-9_-]{43}");
        verify(timelineAiResultTransactionService).store(TASK_ID, SUBJECT_ID, RECORD_ID, result());
    }

    @Test
    void storeResult_storageFailure_restoresResultTokenAndStage() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), eq(pending))).thenReturn(true);
        doThrow(new RuntimeException("db down"))
                .when(timelineAiResultTransactionService).store(anyString(), any(), anyLong(), any());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        ArgumentCaptor<TimelineDraftTask> claimed = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), claimed.capture(), eq(pending));
        assertThat(claimed.getValue().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
    }

    @Test
    void storeResult_concurrentClaim_returns409() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(false);

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_wrongToken_rejectedBeforeTransaction() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.RESULT_PENDING)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, "wrong", result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_beforeInput_rejectedByStage() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.INPUT_PENDING)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    /** question은 선택 필드다 — 도입 이전 형식(null)도 그대로 저장 경로를 탄다. */
    @Test
    void storeResult_withoutQuestion_isStored() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        AiTimelineResultRequest legacy = resultWithQuestion(null);

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, legacy);

        verify(timelineAiResultTransactionService).store(TASK_ID, SUBJECT_ID, RECORD_ID, legacy);
    }

    @Test
    void storeResult_questionTooLong_rejectedBeforeClaim() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, resultWithQuestion("질".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    private AiTimelineResultRequest resultWithQuestion(String question) {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, question,
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_emptyEvents_rejectedBeforeClaim() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, new AiTimelineResultRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_redactsEventTextsBeforeStore() {
        // AI 생성 title/subtitle/question의 v1 PII는 final 저장 경로에 들어가기 전에 token으로 치환된다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        AiTimelineResultRequest request = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "010-1234-5678로 예약한 점심", "메일 yun@example.com",
                "연락처 010-9876-5432 맞나요?",
                OffsetDateTime.of(DATE.atTime(12, 0), KST), OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, request);

        ArgumentCaptor<AiTimelineResultRequest> stored = ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultTransactionService).store(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID), stored.capture());
        AiTimelineResultRequest.Event event = stored.getValue().events().getFirst();
        assertThat(event.title()).isEqualTo(RedactionType.PHONE.token() + "로 예약한 점심");
        assertThat(event.subtitle()).isEqualTo("메일 " + RedactionType.EMAIL.token());
        assertThat(event.question()).isEqualTo("연락처 " + RedactionType.PHONE.token() + " 맞나요?");
    }

    @Test
    void storeResult_boundedRedaction_keepsTitleWithin255WithoutPartialToken() {
        // 원문 247자(shape 검증 통과)가 token 팽창으로 257자가 되면 token 시작 앞에서 절단돼
        // 255 이하를 유지하고 placeholder literal이 잘리지 않는다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        String title = "가".repeat(240) + " a@b.co";
        AiTimelineResultRequest request = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, title, null, null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST), null, List.of("raw-1"))));

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, request);

        ArgumentCaptor<AiTimelineResultRequest> stored = ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultTransactionService).store(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID), stored.capture());
        String storedTitle = stored.getValue().events().getFirst().title();
        assertThat(storedTitle).hasSizeLessThanOrEqualTo(255);
        assertThat(storedTitle).doesNotContain("a@b.co").doesNotContain("[REDACTED");
        assertThat(storedTitle).isEqualTo("가".repeat(240) + " ");
    }

    @Test
    void storeResult_normalizesLeadingWhitespaceBeforeBoundedRedaction() {
        // 앞공백 250 + 'a@b.co'는 trim 길이 6이라 shape 검증을 통과한다. trim 없이 255 bounded 치환을
        // 하면 token 시작(index 250) 앞 절단으로 공백만 남아 transaction trim 후 빈 제목이 저장된다 —
        // persistence와 같은 normalize를 치환 전에 적용해 token이 보존돼야 한다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        String title = " ".repeat(250) + "a@b.co";
        AiTimelineResultRequest request = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, title, null, null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST), null, List.of("raw-1"))));

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, request);

        ArgumentCaptor<AiTimelineResultRequest> stored = ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultTransactionService).store(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID), stored.capture());
        assertThat(stored.getValue().events().getFirst().title()).isEqualTo(RedactionType.EMAIL.token());
    }

    @Test
    void storeResult_titleBlankAfterRedaction_rejectedBeforeClaim() {
        // 필수 title이 치환 후 blank면 shape 위반과 같은 400 계열로 거절한다 — token 선점 전이라
        // RESULT_PENDING이 유지되고 원문 fallback으로 store하지 않는다. 실물 redactor는 normalize 후
        // blank를 만들지 않으므로 stub으로 invariant guard를 검증한다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        doReturn(new RedactionResult(" ", Map.of())).when(privacyRedactor).redactText(anyString(), anyInt());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_redactionFailure_keepsResultTokenUnclaimed() {
        // redaction 실패는 token 선점 전이라 RESULT_PENDING·기존 token이 그대로 유지된다 —
        // 원문 fallback으로 store를 호출하지 않는다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        doThrow(new RuntimeException("redactor down")).when(privacyRedactor).redactText(any(), anyInt());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redactor down");

        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }
}
