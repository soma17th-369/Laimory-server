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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.ProcessStage;
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

/** 입력 DTO 조립과 INPUT → RESULT token/stage 교체를 검증한다. */
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

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(USER_ID, RECORD_ID,
                        new TimelineDraftTask.TimelineWindow(
                                DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay()),
                        TOKEN_HASH, Instant.parse("2026-06-17T03:05:00Z"))
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private void givenRecordAndSources() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(22, 0), ZONE)));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(
                TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.CALENDAR, "raw-1",
                        DATE.atTime(9, 0), DATE.atTime(10, 0),
                        new ObjectMapper().createObjectNode().put("title", "수업")),
                TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.NOTIFICATION, "raw-2",
                        null, null, new ObjectMapper().createObjectNode().put("title", "알림"))));
    }

    @Test
    void getInput_returnsCanonicalInput_andRotatesToResultToken() {
        TimelineDraftTask task = taskAt(ProcessStage.INPUT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
        givenRecordAndSources();
        when(timelineTaskService.rotateTokenAndStage(
                eq(TASK_ID), eq(task), anyString(), eq(ProcessStage.RESULT_PENDING))).thenReturn(true);

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, TASK_TOKEN);

        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.recordDate()).isEqualTo(DATE);
        assertThat(response.recordTimeZone()).isEqualTo(ZONE);
        assertThat(response.window().startAt()).isEqualTo(OffsetDateTime.of(DATE.atStartOfDay(), KST));
        assertThat(response.sourceItems()).hasSize(2);
        assertThat(response.taskToken()).matches("[A-Za-z0-9_-]{43}");
        verify(timelineTaskService).rotateTokenAndStage(
                TASK_ID, task, TaskTokens.hash(response.taskToken()), ProcessStage.RESULT_PENDING);
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
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASH)));

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
        verify(timelineTaskService, never()).rotateTokenAndStage(anyString(), any(), anyString(), any());
    }
}
