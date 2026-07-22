package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.service.FakeAiTimelineAppendService.AppendResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * fake AI direct-write append 단위 검증: 실 AI final transaction 계약의 in-process 대행 —
 * validation(record 존재/DRAFT/owner/기존 rawId 재검사) → Event/Item/junction 저장 → accepted source 삭제,
 * +10분 nudge/clamp 규칙 포함. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class FakeAiTimelineAppendServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;

    @InjectMocks
    private FakeAiTimelineAppendService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TASK_ID = "task-1";
    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @BeforeEach
    void setUp() {
        // 저장 엔티티에 순차 PK를 부여한다(junction이 event/item id를 참조할 수 있도록).
        AtomicLong eventIds = new AtomicLong(100L);
        lenient().when(timelineEventService.save(any())).thenAnswer(invocation -> {
            TimelineEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "timelineEventId", eventIds.incrementAndGet());
            return event;
        });
        AtomicLong itemIds = new AtomicLong(200L);
        lenient().when(timelineItemService.save(any())).thenAnswer(invocation -> {
            TimelineItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "timelineItemId", itemIds.incrementAndGet());
            return item;
        });
    }

    private DailyRecord draftRecord() {
        DailyRecord record = DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private TimelineDraftSourceItem source(long pk, String rawId, LocalDateTime startAt) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.CALENDAR, rawId,
                startAt, null, MAPPER.valueToTree(new CalendarPayload("회의", null, null, false)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", pk);
        return row;
    }

    @Test
    void append_happyPath_savesEventItemsJunction_thenDeletesSources() {
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID))
                .thenReturn(List.of(source(10L, "raw-1", DATE.atTime(9, 0)), source(11L, "raw-2", DATE.atTime(10, 0))));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecord()));
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of());

        AppendResult result = service.append(TASK_ID, RECORD_ID);

        assertThat(result).isEqualTo(AppendResult.SUCCESS);
        // canned Event 1건: UNKNOWN 고정, startAt = source 최소값.
        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(TimelineEventType.UNKNOWN);
        assertThat(eventCaptor.getValue().getStartAt()).isEqualTo(DATE.atTime(9, 0));
        assertThat(eventCaptor.getValue().getTitle()).isEqualTo(FakeAiTimelineAppendService.FAKE_TITLE);
        // source마다 Item 1행 + junction 연결.
        verify(timelineItemService, org.mockito.Mockito.times(2)).save(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineEventItem>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineEventItemService).saveAll(linksCaptor.capture());
        assertThat(linksCaptor.getValue()).hasSize(2);
        assertThat(linksCaptor.getValue()).allSatisfy(link ->
                assertThat(link.getTimelineEventId()).isEqualTo(101L));
        // accepted source(전량)는 final write와 같은 트랜잭션에서 삭제된다.
        verify(timelineDraftSourceItemService).deleteByTaskId(TASK_ID);
    }

    @Test
    void append_nudgesStartAtWhenCollidingWithExistingEvent() {
        // 기존(동결) event가 9:00 시작 → 새 canned event도 9:00이면 +10분 밀린다(실 AI 계약과 동일 규칙).
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID))
                .thenReturn(List.of(source(10L, "raw-1", DATE.atTime(9, 0))));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecord()));
        TimelineEvent frozen = TimelineEvent.of(RECORD_ID, TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "기존", null);
        ReflectionTestUtils.setField(frozen, "timelineEventId", 5L);
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(frozen));
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(5L, 21L)));
        when(timelineItemService.findSavedRawIds(anyCollection(), anyCollection())).thenReturn(Set.of());

        AppendResult result = service.append(TASK_ID, RECORD_ID);

        assertThat(result).isEqualTo(AppendResult.SUCCESS);
        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStartAt()).isEqualTo(DATE.atTime(9, 10));
    }

    @Test
    void append_noSources_isValidationFailed() {
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of());

        assertThat(service.append(TASK_ID, RECORD_ID)).isEqualTo(AppendResult.VALIDATION_FAILED);
        verify(timelineEventService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void append_recordMissing_isValidationFailed() {
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID))
                .thenReturn(List.of(source(10L, "raw-1", DATE.atTime(9, 0))));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThat(service.append(TASK_ID, RECORD_ID)).isEqualTo(AppendResult.VALIDATION_FAILED);
        verify(timelineEventService, never()).save(any());
    }

    @Test
    void append_savedRecord_isValidationFailed() {
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID))
                .thenReturn(List.of(source(10L, "raw-1", DATE.atTime(9, 0))));
        DailyRecord saved = draftRecord();
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThat(service.append(TASK_ID, RECORD_ID)).isEqualTo(AppendResult.VALIDATION_FAILED);
        verify(timelineEventService, never()).save(any());
    }

    @Test
    void append_sourceOwnerMismatch_isValidationFailed() {
        TimelineDraftSourceItem foreign = TimelineDraftSourceItem.of(TASK_ID, 999L, ItemType.CALENDAR, "raw-1",
                DATE.atTime(9, 0), null, MAPPER.valueToTree(new CalendarPayload("회의", null, null, false)));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(foreign));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecord()));

        assertThat(service.append(TASK_ID, RECORD_ID)).isEqualTo(AppendResult.VALIDATION_FAILED);
        verify(timelineEventService, never()).save(any());
    }

    @Test
    void append_rawIdAlreadyInRecordFinals_isValidationFailed() {
        // write 직전 재검사: 이 record의 기존 final Item(junction 경유)에 같은 rawId가 있으면 아무것도 쓰지 않는다.
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID))
                .thenReturn(List.of(source(10L, "raw-dup", DATE.atTime(9, 0))));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecord()));
        TimelineEvent existing = TimelineEvent.of(RECORD_ID, TimelineEventType.UNKNOWN, DATE.atTime(8, 0), null, "기존", null);
        ReflectionTestUtils.setField(existing, "timelineEventId", 5L);
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(existing));
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(5L, 21L)));
        when(timelineItemService.findSavedRawIds(anyCollection(), anyList())).thenReturn(Set.of("raw-dup"));

        assertThat(service.append(TASK_ID, RECORD_ID)).isEqualTo(AppendResult.VALIDATION_FAILED);
        verify(timelineEventService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }
}
