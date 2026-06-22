package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** POST 오케스트레이터 단위 검증. recordDate 도출·SAVED 거절·draft 저장·보상 삭제·디스패치 합성. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private CardSuggestionDispatcher cardSuggestionDispatcher;

    private TimelineDraftTaskService service;

    private static final String VERSION = "v1";
    private static final String ZONE = "Asia/Seoul";
    // 벽시계 정오(12:00) → 정오 경계상 당일(6/17).
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @BeforeEach
    void setUp() {
        service = new TimelineDraftTaskService(
                dailyRecordService, timelineTaskService, timelineDraftSourceItemService,
                cardSuggestionDispatcher, new ObjectMapper());
    }

    private List<SourceItemDto> oneSource() {
        return List.of(new SourceItemDto(0, ItemType.PHOTO, LocalDateTime.of(2026, 6, 17, 9, 0), null, "s",
                new PhotoPayload("u", 1.0, 2.0)));
    }

    @Test
    void createDraftTask_happyPath_savesDraftsThenProcessingThenDispatches() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource());

        assertThat(taskId).isNotBlank();
        // recordDate가 anchor+zone에서 정오 경계로 도출돼 createProcessing에 전달된다.
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), anyString());
        // dispatch는 2-arg(taskId, token) — sourceItems·callbackUrl 없음.
        verify(cardSuggestionDispatcher).dispatch(eq(taskId), anyString());

        // 순서 불변식: draft 저장 → Redis PROCESSING → dispatch.
        InOrder order = inOrder(timelineDraftSourceItemService, timelineTaskService, cardSuggestionDispatcher);
        order.verify(timelineDraftSourceItemService).saveAll(anyList());
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), anyString());
        order.verify(cardSuggestionDispatcher).dispatch(eq(taskId), anyString());
    }

    @Test
    void createDraftTask_savesDraftRowsBuiltFromSources() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftSourceItemService).saveAll(rowsCaptor.capture());
        List<TimelineDraftSourceItem> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(1);
        TimelineDraftSourceItem row = rows.get(0);
        assertThat(row.getTaskId()).isEqualTo(taskId);
        assertThat(row.getUserId()).isEqualTo(0L);
        assertThat(row.getRecordDate()).isEqualTo(DATE);
        assertThat(row.getRecordTimezone()).isEqualTo(ZONE);
        assertThat(row.getRequestItemId()).isEqualTo(0);
        assertThat(row.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(row.getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 0));
        assertThat(row.getSummary()).isEqualTo("s");
        // payload는 discriminator 없는 raw JsonNode.
        assertThat(row.getPayload().get("photoUri").asText()).isEqualTo("u");
        assertThat(row.getPayload().has("itemType")).isFalse();
    }

    @Test
    void createDraftTask_storesOnlyTokenHash_notRawToken() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource());

        // Redis에 저장되는 값(createProcessing 인자)은 해시, AI에 전달되는 값(dispatch 인자)은 원문이어야 한다.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), hashCaptor.capture());
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(cardSuggestionDispatcher).dispatch(eq(taskId), tokenCaptor.capture());

        String storedHash = hashCaptor.getValue();
        String dispatchedToken = tokenCaptor.getValue();
        assertThat(dispatchedToken).isNotBlank();
        assertThat(storedHash).isNotEqualTo(dispatchedToken);
        assertThat(storedHash).isEqualTo(CallbackTokens.hash(dispatchedToken));
    }

    @Test
    void createDraftTask_reusesDraftRecord_doesNotReject() {
        DailyRecord draft = DailyRecord.createDraft(0L, DATE, ZONE);
        ReflectionTestUtils.setField(draft, "dailyRecordId", 3L);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(draft));

        String taskId = service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), anyString());
    }

    @Test
    void createDraftTask_rejectsSavedRecord() {
        DailyRecord saved = DailyRecord.createDraft(0L, DATE, ZONE);
        ReflectionTestUtils.setField(saved, "dailyRecordId", 5L);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), any(), anyString());
        verify(cardSuggestionDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void createDraftTask_whenRedisFails_compensatesByDeletingDraftsAndRethrows() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("redis down"))
                .when(timelineTaskService).createProcessing(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis down");

        // 보상 삭제: 방금 저장한 draft 행을 taskId로 지운다(고아 draft 방지). dispatch는 호출되지 않는다.
        verify(timelineDraftSourceItemService).deleteByTaskId(anyString());
        verify(cardSuggestionDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void createDraftTask_whenDispatchThrows_marksFailedKeepsDraftsAndReturnsTaskId() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("boom"))
                .when(cardSuggestionDispatcher).dispatch(anyString(), anyString());

        // dispatch가 동기 예외를 던져도 taskId는 반환되고 task는 FAILED로 고정된다(고아 PROCESSING 방지).
        String taskId = service.createDraftTask(VERSION, ANCHOR, ZONE, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), anyString());
        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).markFailed(eq(taskId), eq(DATE), errorCaptor.capture(), anyString());
        assertThat(errorCaptor.getValue()).contains("boom");
        // dispatch 실패는 draft를 보존한다(cleanup이 정리). 보상 삭제 없음.
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_rejectsNullRecordAnchorAt() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, null, ZONE, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullRecordTimeZone() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, null, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsEmptySourceItems() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullSourceItemId() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(null, ItemType.PHOTO, null, null, "s", new PhotoPayload("u", 1.0, 2.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsDuplicateSourceItemId() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, null, null, "s", new PhotoPayload("u", 1.0, 2.0)),
                new SourceItemDto(0, ItemType.PHOTO, null, null, "s2", new PhotoPayload("v", 3.0, 4.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullItemType() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, null, null, null, "s", new PhotoPayload("u", 1.0, 2.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullPayload() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, null, null, "s", null));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, ANCHOR, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
