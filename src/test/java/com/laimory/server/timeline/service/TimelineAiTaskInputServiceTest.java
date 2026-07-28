package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TaskTokenFixtures.derivedTokenHashes;
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
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AI 입력 조회 단위 검증: 단계 토큰 게이트가 데이터 조회보다 먼저 걸리는지, record timezone offset 변환,
 * 다음 토큰 발급의 재시도 안정성, PROCESSING TTL 갱신 시점. 인프라 0.
 */
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
    private static final String INPUT_TOKEN = "raw-input-token";
    private static final TimelineDraftTask.TokenHashes TOKEN_HASHES =
            derivedTokenHashes(INPUT_TOKEN, TASK_ID);

    private TimelineDraftTask processingTask() {
        return TimelineDraftTask.processing(USER_ID, RECORD_ID,
                new TimelineDraftTask.TimelineWindow(DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay()),
                TOKEN_HASHES, Instant.parse("2026-06-17T03:05:00Z"));
    }

    private void givenProcessingTaskWithRecordAndSources() {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));
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
    void getInput_returnsCanonicalInput_withOffsetTimesAndNextToken() {
        givenProcessingTaskWithRecordAndSources();

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, INPUT_TOKEN);

        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.recordDate()).isEqualTo(DATE);
        assertThat(response.recordTimeZone()).isEqualTo(ZONE);
        // Redis의 local window에 record timezone offset이 붙는다(wall-clock 유지).
        assertThat(response.window().startAt()).isEqualTo(OffsetDateTime.of(DATE.atStartOfDay(), KST));
        assertThat(response.window().endAt())
                .isEqualTo(OffsetDateTime.of(DATE.plusDays(1).atStartOfDay(), KST));
        assertThat(response.sourceItems()).hasSize(2);
        assertThat(response.sourceItems().get(0).rawId()).isEqualTo("raw-1");
        assertThat(response.sourceItems().get(0).startAt())
                .isEqualTo(OffsetDateTime.of(DATE.atTime(9, 0), KST));
        // 시간 미상 아이템은 null을 그대로 통과시킨다.
        assertThat(response.sourceItems().get(1).startAt()).isNull();
        assertThat(response.sourceItems().get(1).endAt()).isNull();
        assertThat(response.sourceItems().get(1).payload().get("title").asText()).isEqualTo("알림");
        // 다음 단계 토큰은 제시된 입력 토큰에서 파생한다.
        assertThat(response.resultToken()).isEqualTo(TaskTokens.deriveResultToken(INPUT_TOKEN, TASK_ID));
    }

    @Test
    void getInput_retriedWithSameToken_returnsSameNextToken() {
        givenProcessingTaskWithRecordAndSources();

        String first = service.getInput(VERSION, TASK_ID, INPUT_TOKEN).resultToken();
        String second = service.getInput(VERSION, TASK_ID, INPUT_TOKEN).resultToken();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void getInput_refreshesProcessingTtlAfterAssemblingResponse() {
        givenProcessingTaskWithRecordAndSources();

        service.getInput(VERSION, TASK_ID, INPUT_TOKEN);

        verify(timelineTaskService).refreshProcessing(TASK_ID, processingTask());
    }

    @Test
    void getInput_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInput(VERSION, "missing", INPUT_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_wrongToken_throws401_beforeReadingPersonalData() {
        // 토큰 게이트가 record·source 조회보다 먼저다 — /s/api엔 principal이 없어 토큰만이 인증 수단이다.
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, "wrong-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
        verify(timelineTaskService, never()).refreshProcessing(anyString(), any());
    }

    @Test
    void getInput_resultStageToken_throws401() {
        // 단계가 다르면 hash가 다르다 — 결과 저장 토큰으로 입력을 조회할 수 없다.
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID,
                TaskTokens.deriveResultToken(INPUT_TOKEN, TASK_ID)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
    }

    @Test
    void getInput_terminalTask_throws1017() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, RECORD_ID, TOKEN_HASHES)));

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, INPUT_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(dailyRecordService, timelineDraftSourceItemService);
    }

    @Test
    void getInput_recordMissing_throws404() {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInput(VERSION, TASK_ID, INPUT_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-404));
        verify(timelineTaskService, never()).refreshProcessing(anyString(), any());
    }

    @Test
    void getInput_emptyStaging_returnsEmptyList() {
        // 채택 후 재조회 등으로 source가 비어도 조회 자체는 실패하지 않는다(결과 저장 단계가 판정한다).
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(22, 0), ZONE)));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of());

        assertThat(service.getInput(VERSION, TASK_ID, INPUT_TOKEN).sourceItems()).isEmpty();
    }

    @Test
    void getInput_convertsWithRecordTimezone_notServerZone() {
        // 서버 기본 timezone이 아니라 record_timezone이 기준이다.
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(
                DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(22, 0), "America/New_York")));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(
                TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.CALENDAR, "raw-1",
                        LocalDateTime.of(2026, 6, 17, 9, 0), null,
                        new ObjectMapper().createObjectNode())));

        AiTimelineTaskInputResponse response = service.getInput(VERSION, TASK_ID, INPUT_TOKEN);

        assertThat(response.sourceItems().get(0).startAt())
                .isEqualTo(OffsetDateTime.of(LocalDateTime.of(2026, 6, 17, 9, 0), ZoneOffset.ofHours(-4)));
    }
}
