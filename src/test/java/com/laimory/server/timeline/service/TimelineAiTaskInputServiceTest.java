package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.privacy.RedactionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 입력 DTO 조립, INPUT → RESULT token/stage 교체, 응답 유실 뒤 재발급을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineAiTaskInputServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;

    private static final Instant NOW = Instant.parse("2026-06-17T03:10:00Z");
    private static final Duration RETRY_WINDOW = Duration.ofSeconds(15);

    private TimelineAiTaskInputService service;

    @BeforeEach
    void setUp() {
        service = new TimelineAiTaskInputService(timelineTaskService, dailyRecordService,
                timelineDraftSourceItemService, Clock.fixed(NOW, ZoneOffset.UTC), RETRY_WINDOW);
    }

    private static final String VERSION = "v1";
    private static final String TASK_ID = "t";
    private static final java.util.UUID SUBJECT_ID = com.laimory.server.testsupport.TestSubjects.id(7L);
    private static final long RECORD_ID = 42L;
    private static final String ZONE = "Asia/Seoul";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final String TASK_TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TASK_TOKEN);

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(SUBJECT_ID, RECORD_ID,
                        new TimelineDraftTask.TimelineWindow(
                                DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay()),
                        TOKEN_HASH, Instant.parse("2026-06-17T03:05:00Z"))
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private void givenRecordAndSources() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(SUBJECT_ID, DATE, DATE.atTime(22, 0), ZONE)));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(
                TimelineDraftSourceItem.of(TASK_ID, SUBJECT_ID, ItemType.CALENDAR, "raw-1",
                        DATE.atTime(9, 0), DATE.atTime(10, 0),
                        new ObjectMapper().createObjectNode().put("title", "수업")),
                TimelineDraftSourceItem.of(TASK_ID, SUBJECT_ID, ItemType.NOTIFICATION, "raw-2",
                        null, null, new ObjectMapper().createObjectNode().put("title", "알림"))));
    }

    @Test
    void getInput_returnsCanonicalInput_andRotatesToResultToken() {
        TimelineDraftTask task = taskAt(ProcessStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        givenRecordAndSources();
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(task), any())).thenReturn(true);

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, TASK_TOKEN);

        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.recordDate()).isEqualTo(DATE);
        assertThat(response.recordTimeZone()).isEqualTo(ZONE);
        assertThat(response.window().startAt()).isEqualTo(OffsetDateTime.of(DATE.atStartOfDay(), KST));
        assertThat(response.sourceItems()).hasSize(2);
        assertThat(response.taskToken()).matches("[A-Za-z0-9_-]{43}");
        ArgumentCaptor<TimelineDraftTask> rotated = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), eq(task), rotated.capture());
        assertThat(rotated.getValue().tokenHash()).isEqualTo(TaskTokens.hash(response.taskToken()));
        assertThat(rotated.getValue().stage()).isEqualTo(ProcessStage.RESULT_PENDING);
        // 재시도 receipt: 소비된 input token hash와 첫 요청 도착 기준 창.
        assertThat(rotated.getValue().retryReceipt().previousTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(rotated.getValue().retryReceipt().retryableUntil()).isEqualTo(NOW.plus(RETRY_WINDOW));
        assertThat(rotated.getValue().retryReceipt().committedAt()).isNull();
    }

    @Test
    void getInput_replacesClientPhotoUriWithToken_withoutMutatingStoredPayload() {
        // storage payload는 이미 v1 치환 저장본 — projection은 storage 예외였던 clientPhotoUri만
        // 고정 token으로 바꾼다. 엔티티 payload(Hibernate 관리 필드)는 변형되지 않아야 한다.
        TimelineDraftTask task = taskAt(ProcessStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(SUBJECT_ID, DATE, DATE.atTime(22, 0), ZONE)));
        ObjectNode photoPayload = new ObjectMapper().createObjectNode()
                .put("filename", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg")
                .put("clientPhotoUri", "content://media/external/images/42")
                .put("latitude", 37.5665);
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(
                TimelineDraftSourceItem.of(TASK_ID, SUBJECT_ID, ItemType.PHOTO, "raw-1",
                        DATE.atTime(9, 0), null, photoPayload)));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(task), any())).thenReturn(true);

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, TASK_TOKEN);

        JsonNode projected = response.sourceItems().getFirst().payload();
        assertThat(projected.get("clientPhotoUri").textValue()).isEqualTo(RedactionType.DEVICE_URI.token());
        // 다른 필드는 그대로 전달된다(값만 치환 — 필드 집합 불변).
        assertThat(projected.get("filename").textValue()).isEqualTo("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
        assertThat(projected.get("latitude").doubleValue()).isEqualTo(37.5665);
        // 저장 payload 원본은 미변형(deep copy 뒤 치환).
        assertThat(photoPayload.get("clientPhotoUri").textValue())
                .isEqualTo("content://media/external/images/42");
    }

    @Test
    void getInput_payloadWithoutClientPhotoUri_passesSameInstanceThrough() {
        // clientPhotoUri가 없는 payload는 복사 비용 없이 기존 인스턴스를 그대로 반환한다(read-only 직렬화).
        TimelineDraftTask task = taskAt(ProcessStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(SUBJECT_ID, DATE, DATE.atTime(22, 0), ZONE)));
        ObjectNode calendarPayload = new ObjectMapper().createObjectNode().put("title", "수업");
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(
                TimelineDraftSourceItem.of(TASK_ID, SUBJECT_ID, ItemType.CALENDAR, "raw-1",
                        DATE.atTime(9, 0), DATE.atTime(10, 0), calendarPayload)));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(task), any())).thenReturn(true);

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, TASK_TOKEN);

        assertThat(response.sourceItems().getFirst().payload()).isSameAs(calendarPayload);
    }

    @Test
    void getInput_afterInputConsumed_rejectedBeforePersonalData() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.RESULT_PENDING)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_wrongToken_rejectedBeforePersonalData() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.INPUT_PENDING)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, "wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_terminalTask_throws1017() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.success(SUBJECT_ID, RECORD_ID, TOKEN_HASH)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_recordMissing_doesNotRotateToken() {
        TimelineDraftTask task = taskAt(ProcessStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-404));
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
    }

    /** 회전이 끝나 result token을 들고 있는 상태 — 입력 응답이 유실된 AI는 여기서 옛 input token으로 다시 온다. */
    private TimelineDraftTask rotatedWithReceipt(java.time.Instant retryableUntil) {
        return taskAt(ProcessStage.INPUT_PENDING)
                .withTokenAndStage(TaskTokens.hash("result-token"), ProcessStage.RESULT_PENDING)
                .withRetryReceipt(new TimelineDraftTask.RetryReceipt(TOKEN_HASH, null, null, retryableUntil));
    }

    @Test
    void getInput_replayAfterLostResponse_reissuesResultToken() {
        TimelineDraftTask rotated = rotatedWithReceipt(NOW.plus(RETRY_WINDOW));
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(rotated));
        givenRecordAndSources();
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(rotated), any())).thenReturn(true);

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, TASK_TOKEN);

        // 입력 payload는 DB에서 그대로 다시 조립된다.
        assertThat(response.sourceItems()).hasSize(2);
        assertThat(response.taskToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(TaskTokens.matches(response.taskToken(), rotated.tokenHash())).isFalse();

        ArgumentCaptor<TimelineDraftTask> reissued = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), eq(rotated), reissued.capture());
        assertThat(reissued.getValue().stage()).isEqualTo(ProcessStage.RESULT_PENDING);
        assertThat(reissued.getValue().tokenHash()).isEqualTo(TaskTokens.hash(response.taskToken()));
        // 재발급이 창을 갱신하면 마감이 계속 미끄러진다 — receipt는 최초 값 그대로여야 한다.
        assertThat(reissued.getValue().retryReceipt()).isEqualTo(rotated.retryReceipt());
    }

    @Test
    void getInput_replayAfterWindowExpired_rejectedBeforePersonalData() {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(rotatedWithReceipt(NOW)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
    }

    @Test
    void getInput_replayWithUnknownToken_rejectedBeforePersonalData() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(rotatedWithReceipt(NOW.plus(RETRY_WINDOW))));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, "third-party"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_replayLostRace_returns409() {
        TimelineDraftTask rotated = rotatedWithReceipt(NOW.plus(RETRY_WINDOW));
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(rotated));
        givenRecordAndSources();
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(rotated), any())).thenReturn(false);

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
    }
}
