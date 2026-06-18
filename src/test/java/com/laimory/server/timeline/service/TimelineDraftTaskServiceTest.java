package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** POST 오케스트레이터 단위 검증. SAVED 거절·가드·taskId 발급·토큰 발급·디스패치 합성. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private CardSuggestionDispatcher cardSuggestionDispatcher;

    private TimelineDraftTaskService service;

    private static final String BASE_URL = "http://localhost:8080";
    private static final String VERSION = "v1";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @BeforeEach
    void setUp() {
        service = new TimelineDraftTaskService(
                dailyRecordService, timelineTaskService, cardSuggestionDispatcher, BASE_URL);
    }

    private List<SourceItemDto> oneSource() {
        return List.of(new SourceItemDto(0, LocalDateTime.of(2026, 6, 17, 9, 0), null, "s",
                new PhotoPayload("u", 1.0, 2.0)));
    }

    @Test
    void createDraftTask_happyPath_createsProcessingAndDispatchesWithCallbackUrl() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, DATE, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), anyString());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(cardSuggestionDispatcher).dispatch(eq(taskId), anyString(), any(), urlCaptor.capture());
        // 콜백 URL에 요청 버전이 그대로 실린다.
        assertThat(urlCaptor.getValue()).isEqualTo(
                BASE_URL + "/s/api/" + VERSION + "/timeline/daily-records/draft-tasks/" + taskId + "/callback");
    }

    @Test
    void createDraftTask_storesOnlyTokenHash_notRawToken() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, DATE, oneSource());

        // Redis에 저장되는 값(createProcessing 인자)은 해시, AI에 전달되는 값(dispatch 인자)은 원문이어야 한다.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), hashCaptor.capture());
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(cardSuggestionDispatcher).dispatch(eq(taskId), tokenCaptor.capture(), any(), anyString());

        String storedHash = hashCaptor.getValue();
        String dispatchedToken = tokenCaptor.getValue();
        assertThat(dispatchedToken).isNotBlank();
        assertThat(storedHash).isNotEqualTo(dispatchedToken);
        assertThat(storedHash).isEqualTo(CallbackTokens.hash(dispatchedToken));
    }

    @Test
    void createDraftTask_reusesDraftRecord_doesNotReject() {
        DailyRecord draft = DailyRecord.createDraft(0L, DATE);
        ReflectionTestUtils.setField(draft, "id", 3L);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(draft));

        String taskId = service.createDraftTask(VERSION, DATE, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), anyString());
    }

    @Test
    void createDraftTask_rejectsSavedRecord() {
        DailyRecord saved = DailyRecord.createDraft(0L, DATE);
        ReflectionTestUtils.setField(saved, "id", 5L);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, DATE, oneSource()))
                .isInstanceOf(IllegalStateException.class);
        verify(timelineTaskService, never()).createProcessing(anyString(), any(), anyString());
        verify(cardSuggestionDispatcher, never()).dispatch(anyString(), anyString(), any(), anyString());
    }

    @Test
    void createDraftTask_rejectsNullRecordDate() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, null, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsEmptySourceItems() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, DATE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
