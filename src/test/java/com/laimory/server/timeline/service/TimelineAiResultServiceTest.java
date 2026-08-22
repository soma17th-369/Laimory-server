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
import static org.mockito.Mockito.times;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** 결과 저장 선점·commit 뒤 회전·응답 유실 재시도 멱등 처리와 DB 실패 보상을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineAiResultServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineAiResultTransactionService timelineAiResultTransactionService;
    // 실물 redactor를 spy로 주입한다 — 치환 검증은 실물 동작으로, 실패 주입만 stubbing으로 한다.
    @Spy
    private PrivacyRedactor privacyRedactor = new PrivacyRedactor();


    private static final String VERSION = "v1";
    private static final String TASK_ID = "t";
    private static final UUID SUBJECT_ID = id(7L);
    private static final long RECORD_ID = 42L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String TASK_TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TASK_TOKEN);
    private static final Instant NOW = Instant.parse("2026-06-17T03:10:00Z");
    private static final Duration RETRY_WINDOW = Duration.ofSeconds(15);

    private TimelineAiResultService service;

    @BeforeEach
    void setUp() {
        service = new TimelineAiResultService(timelineTaskService, timelineAiResultTransactionService,
                privacyRedactor, Clock.fixed(NOW, ZoneOffset.UTC), RETRY_WINDOW);
    }

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(SUBJECT_ID, RECORD_ID, null, TOKEN_HASH,
                        Instant.parse("2026-06-17T03:05:00Z"))
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private AiTimelineResultRequest result() {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, "점심에 누구와 함께였나요?", null, null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_claimsWithoutRotating_thenRotatesAfterCommit() {
        // 선점 CAS는 token·stage를 그대로 두고 지문만 남긴다 — MySQL이 실패해도 AI의 token이 살아 있어
        // 재시도가 특수 경로 없이 정상 경로로 다시 돈다. 회전과 stage 전이는 commit 뒤 한 번에 일어난다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);

        AiTimelineResultResponse response = service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        ArgumentCaptor<TimelineDraftTask> written = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService, times(2)).replaceProcessing(eq(TASK_ID), any(), written.capture());

        TimelineDraftTask claimed = written.getAllValues().get(0);
        assertThat(claimed.stage()).isEqualTo(ProcessStage.RESULT_PENDING);
        assertThat(claimed.tokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(claimed.retryReceipt().previousTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(claimed.retryReceipt().claimedAt()).isEqualTo(NOW);
        assertThat(claimed.retryReceipt().retryableUntil()).isEqualTo(NOW.plus(RETRY_WINDOW));

        TimelineDraftTask committed = written.getAllValues().get(1);
        assertThat(committed.stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
        assertThat(committed.matchesToken(response.taskToken())).isTrue();
        // 창은 선점 시점에 정해지고 commit CAS가 갱신하지 않는다(기산점 = 첫 요청 도착).
        assertThat(committed.retryReceipt().retryableUntil()).isEqualTo(NOW.plus(RETRY_WINDOW));

        assertThat(response.taskToken()).matches("[A-Za-z0-9_-]{43}");
        verify(timelineAiResultTransactionService).store(TASK_ID, SUBJECT_ID, RECORD_ID, result());
    }

    @Test
    void storeResult_storageFailure_releasesClaimAndKeepsResultToken() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);
        doThrow(new RuntimeException("db down"))
                .when(timelineAiResultTransactionService).store(anyString(), any(), anyLong(), any());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        // 보상은 선점 제거뿐이다 — token은 애초에 바뀌지 않았으므로 AI 재시도가 정상 경로로 다시 돈다.
        ArgumentCaptor<TimelineDraftTask> claimed = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), claimed.capture(), eq(pending));
        assertThat(claimed.getValue().stage()).isEqualTo(ProcessStage.RESULT_PENDING);
        assertThat(claimed.getValue().tokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(claimed.getValue().retryReceipt()).isNotNull();
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
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);
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
                TimelineEventType.MEAL, "점심", null, question, null, null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_placeOrAddressTooLong_rejectedBeforeClaim() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, resultWithPlaceAndAddress("장".repeat(256), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, resultWithPlaceAndAddress(null, "주".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_blankPlaceAndAddress_normalizedToNullBeforeStore() {
        // 공백 문자열은 치환 사본 단계에서 이미 null(값 없음)로 수렴한다 — transaction의 trimToNull과 같은 규칙이다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, resultWithPlaceAndAddress("   ", ""));

        ArgumentCaptor<AiTimelineResultRequest> stored = ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultTransactionService).store(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID), stored.capture());
        AiTimelineResultRequest.Event event = stored.getValue().events().getFirst();
        assertThat(event.place()).isNull();
        assertThat(event.address()).isNull();
    }

    private AiTimelineResultRequest resultWithPlaceAndAddress(String place, String address) {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, null, place, address,
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
        // AI 생성 title/subtitle/question/place/address의 v1 PII는 final 저장 경로에 들어가기 전에 token으로 치환된다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);
        AiTimelineResultRequest request = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "010-1234-5678로 예약한 점심", "메일 yun@example.com",
                "연락처 010-9876-5432 맞나요?", "성수동 카페 010-1111-2222",
                "서울특별시 성동구 아차산로 17, 문의 shop@example.com",
                OffsetDateTime.of(DATE.atTime(12, 0), KST), OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, request);

        ArgumentCaptor<AiTimelineResultRequest> stored = ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultTransactionService).store(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID), stored.capture());
        AiTimelineResultRequest.Event event = stored.getValue().events().getFirst();
        assertThat(event.title()).isEqualTo(RedactionType.PHONE.token() + "로 예약한 점심");
        assertThat(event.subtitle()).isEqualTo("메일 " + RedactionType.EMAIL.token());
        assertThat(event.question()).isEqualTo("연락처 " + RedactionType.PHONE.token() + " 맞나요?");
        assertThat(event.place()).isEqualTo("성수동 카페 " + RedactionType.PHONE.token());
        assertThat(event.address())
                .isEqualTo("서울특별시 성동구 아차산로 17, 문의 " + RedactionType.EMAIL.token());
    }

    @Test
    void storeResult_boundedRedaction_keepsTitleWithin255WithoutPartialToken() {
        // 원문 247자(shape 검증 통과)가 token 팽창으로 257자가 되면 token 시작 앞에서 절단돼
        // 255 이하를 유지하고 placeholder literal이 잘리지 않는다.
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);
        String title = "가".repeat(240) + " a@b.co";
        AiTimelineResultRequest request = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, title, null, null, null, null,
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
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);
        String title = " ".repeat(250) + "a@b.co";
        AiTimelineResultRequest request = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, title, null, null, null, null,
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

    private AiTimelineResultRequest resultWithTitle(String title) {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, title, null, "점심에 누구와 함께였나요?", null, null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    /** 1차 저장을 실제로 한 번 돌려 commit 확정 task를 얻는다 — 지문도 실물 치환 경로로 만들어진다. */
    private TimelineDraftTask storeOnceAndCaptureCommitted(AiTimelineResultRequest request) {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(taskAt(ProcessStage.RESULT_PENDING)));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), any())).thenReturn(true);
        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, request);
        ArgumentCaptor<TimelineDraftTask> written = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService, times(2)).replaceProcessing(eq(TASK_ID), any(), written.capture());
        return written.getAllValues().get(1);
    }

    @Test
    void storeResult_replayAfterLostResponse_reissuesWithoutStoringAgain() {
        TimelineDraftTask committed = storeOnceAndCaptureCommitted(result());
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(committed));

        AiTimelineResultResponse replay = service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        assertThat(replay.taskToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(committed.matchesToken(replay.taskToken())).isFalse();
        // graph는 다시 쓰지 않는다.
        verify(timelineAiResultTransactionService, times(1)).store(anyString(), any(), anyLong(), any());

        ArgumentCaptor<TimelineDraftTask> written = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService, times(3)).replaceProcessing(eq(TASK_ID), any(), written.capture());
        TimelineDraftTask reissued = written.getAllValues().get(2);
        assertThat(reissued.stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
        assertThat(reissued.matchesToken(replay.taskToken())).isTrue();
        // 제자리 회전 — receipt와 창은 그대로다(창이 미끄러지면 마감이 무의미해진다).
        assertThat(reissued.retryReceipt()).isEqualTo(committed.retryReceipt());
    }

    @Test
    void storeResult_replayWithDifferentPayload_isIgnoredNotApplied() {
        // 첫 저장이 이미 commit돼 staging이 사라졌고 graph는 append-only다 — 다른 body가 와도 적용할
        // 경로가 없으므로 내용을 대조하지 않고 무시한다. 저장된 graph는 그대로다.
        TimelineDraftTask committed = storeOnceAndCaptureCommitted(result());
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(committed));

        AiTimelineResultResponse replay =
                service.storeResult(VERSION, TASK_ID, TASK_TOKEN, resultWithTitle("저녁"));

        // graph는 다시 쓰지 않고 새 token만 재발급한다.
        assertThat(committed.matchesToken(replay.taskToken())).isFalse();
        verify(timelineAiResultTransactionService, times(1)).store(anyString(), any(), anyLong(), any());
    }

    @Test
    void storeResult_replayAfterWindowExpired_returns401() {
        TimelineDraftTask committed = storeOnceAndCaptureCommitted(result());
        TimelineDraftTask.RetryReceipt receipt = committed.retryReceipt();
        TimelineDraftTask expired = committed.withRetryReceipt(new TimelineDraftTask.RetryReceipt(
                receipt.previousTokenHash(), receipt.claimedAt(), NOW));
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verify(timelineTaskService, times(2)).replaceProcessing(eq(TASK_ID), any(), any());
    }

    @Test
    void storeResult_replayOnTerminalTask_returns401() {
        // terminal 전이가 receipt를 버리므로 callback 이후의 재요청은 인지 대상이 아니다.
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(
                TimelineDraftTask.success(SUBJECT_ID, RECORD_ID, TaskTokens.hash("callback-token"))));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_whileAnotherAttemptHoldsClaim_returns409() {
        // 창이 지난 선점도 재선점하지 않는다 — 그 시도가 아직 transaction 중이면 graph가 중복 저장된다.
        TimelineDraftTask claimed = taskAt(ProcessStage.RESULT_PENDING)
                .withRetryReceipt(new TimelineDraftTask.RetryReceipt(TOKEN_HASH, NOW, NOW));
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(claimed));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
    }

    @Test
    void storeResult_replayLostRace_returns409() {
        // 진 쪽이 방금 만든 token은 저장된 적이 없다 — 돌려주면 callback에서 401이 되므로 409로 끝낸다.
        TimelineDraftTask committed = storeOnceAndCaptureCommitted(result());
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(committed));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(committed), any())).thenReturn(false);

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verify(timelineAiResultTransactionService, times(1)).store(anyString(), any(), anyLong(), any());
    }
}
