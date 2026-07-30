package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TaskStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 단일 task token 게이트, 입력 DTO 조립과 Redis stage 전이를 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineAiTaskInputServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @InjectMocks
    private TimelineAiTaskInputService service;

    private static final String VERSION = "v1";
    private static final String TASK_ID = "t";
    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final String ZONE = "Asia/Seoul";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final String TASK_TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TASK_TOKEN);

    private TimelineDraftTask taskAt(TaskStage stage) {
        return TimelineDraftTask.processing(USER_ID, RECORD_ID,
                        new TimelineDraftTask.TimelineWindow(
                                DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay()),
                        TOKEN_HASH, Instant.parse("2026-06-17T03:05:00Z"))
                .withStage(stage);
    }

    private void givenRecordAndSources() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(22, 0), ZONE)));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(
                TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.CALENDAR, "raw-1",
                        DATE.atTime(9, 0), DATE.atTime(10, 0),
                        new ObjectMapper().createObjectNode().put("title", "스탠드업")),
                TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.NOTIFICATION, "raw-2",
                        null, null, new ObjectMapper().createObjectNode().put("title", "알림"))));
    }

    @Test
    void getInput_returnsCanonicalInput_andAdvancesInitialStage() {
        TimelineDraftTask task = taskAt(TaskStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        givenRecordAndSources();
        when(timelineTaskService.transitionStage(TASK_ID, task, TaskStage.RESULT_PENDING)).thenReturn(true);

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, TASK_TOKEN);

        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.recordDate()).isEqualTo(DATE);
        assertThat(response.recordTimeZone()).isEqualTo(ZONE);
        assertThat(response.window().startAt()).isEqualTo(OffsetDateTime.of(DATE.atStartOfDay(), KST));
        assertThat(response.sourceItems()).hasSize(2);
        assertThat(response.sourceItems().get(0).startAt())
                .isEqualTo(OffsetDateTime.of(DATE.atTime(9, 0), KST));
        assertThat(response.sourceItems().get(1).startAt()).isNull();
        verify(timelineTaskService).transitionStage(TASK_ID, task, TaskStage.RESULT_PENDING);
    }

    @Test
    void getInput_retryAtResultPending_refreshesTtl() {
        TimelineDraftTask task = taskAt(TaskStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        givenRecordAndSources();
        when(timelineTaskService.refreshProcessing(TASK_ID, task)).thenReturn(true);

        assertThat(service.getInput(VERSION, TASK_ID, TASK_TOKEN).sourceItems()).hasSize(2);

        verify(timelineTaskService).refreshProcessing(TASK_ID, task);
    }

    @Test
    void getInput_wrongToken_rejectedBeforePersonalData() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskStage.INPUT_PENDING)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, "wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_afterResultStarted_rejectedBeforePersonalData() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskStage.RESULT_WRITING)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_terminalTask_throws1017() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASH)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_recordMissing_doesNotAdvanceStage() {
        TimelineDraftTask task = taskAt(TaskStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, TASK_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-404));
        verify(timelineTaskService, never()).transitionStage(anyString(), any(), any());
    }
}
