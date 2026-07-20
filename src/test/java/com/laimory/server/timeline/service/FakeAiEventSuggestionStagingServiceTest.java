package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** fake AI staging의 canned 이벤트 산출(시각 폴백 체인 포함)과 FK 배정을 검증한다. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class FakeAiEventSuggestionStagingServiceTest {

    private static final String TASK_ID = "task-1";
    private static final long SUGGESTION_ID = 77L;

    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;
    @InjectMocks
    private FakeAiEventSuggestionStagingService service;

    private TimelineDraftSourceItem source(LocalDateTime startAt, LocalDateTime endAt) {
        return TimelineDraftSourceItem.of(TASK_ID, 1L, ItemType.CALENDAR, "r", startAt, endAt, null);
    }

    /** save 결과의 generated id로 FK를 배정하므로, mock 반환 엔티티에 id를 심어 흉내낸다(엔티티는 setter 없음). */
    private TimelineDraftEventSuggestion savedSuggestion() {
        TimelineDraftEventSuggestion saved = TimelineDraftEventSuggestion.of(
                TASK_ID, 1L, TimelineEventType.UNKNOWN.name(), LocalDateTime.of(2000, 1, 2, 9, 0), null, "saved", null);
        ReflectionTestUtils.setField(saved, "timelineDraftEventSuggestionId", SUGGESTION_ID);
        return saved;
    }

    @Test
    void stage_returnsFalseWithoutWrites_whenNoSources() {
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of());

        boolean staged = service.stage(TASK_ID);

        assertThat(staged).isFalse();
        verify(timelineDraftEventSuggestionService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void stage_stagesOneCannedEventAndAssignsAllSources() {
        TimelineDraftSourceItem s1 = source(LocalDateTime.of(2000, 1, 2, 10, 0), LocalDateTime.of(2000, 1, 2, 11, 0));
        TimelineDraftSourceItem s2 = source(LocalDateTime.of(2000, 1, 2, 9, 0), LocalDateTime.of(2000, 1, 2, 13, 0));
        TimelineDraftSourceItem s3 = source(null, null);
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(s1, s2, s3));
        when(timelineDraftEventSuggestionService.save(any())).thenReturn(savedSuggestion());

        boolean staged = service.stage(TASK_ID);

        assertThat(staged).isTrue();
        ArgumentCaptor<TimelineDraftEventSuggestion> captor = ArgumentCaptor.forClass(TimelineDraftEventSuggestion.class);
        verify(timelineDraftEventSuggestionService).save(captor.capture());
        TimelineDraftEventSuggestion suggestion = captor.getValue();
        assertThat(suggestion.getTaskId()).isEqualTo(TASK_ID);
        // 실 AI writer 계약 모사: eventType을 명시적으로 기록한다(fake는 분류하지 않으므로 UNKNOWN 고정).
        assertThat(suggestion.getEventType()).isEqualTo(TimelineEventType.UNKNOWN.name());
        assertThat(suggestion.getStartAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 9, 0));   // min startAt
        assertThat(suggestion.getEndAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 13, 0));    // max endAt
        assertThat(suggestion.getTitle()).isEqualTo(FakeAiEventSuggestionStagingService.FAKE_TITLE);
        assertThat(suggestion.getSubtitle()).isNull();

        assertThat(List.of(s1, s2, s3))
                .allSatisfy(s -> assertThat(s.getTimelineDraftEventSuggestionId()).isEqualTo(SUGGESTION_ID));
        verify(timelineDraftSourceItemService).saveAll(List.of(s1, s2, s3));
    }

    @Test
    void stage_fallsBackToMinEndAt_whenAllStartAtNull() {
        TimelineDraftSourceItem s1 = source(null, LocalDateTime.of(2000, 1, 2, 12, 0));
        TimelineDraftSourceItem s2 = source(null, LocalDateTime.of(2000, 1, 2, 14, 0));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(s1, s2));
        when(timelineDraftEventSuggestionService.save(any())).thenReturn(savedSuggestion());

        service.stage(TASK_ID);

        ArgumentCaptor<TimelineDraftEventSuggestion> captor = ArgumentCaptor.forClass(TimelineDraftEventSuggestion.class);
        verify(timelineDraftEventSuggestionService).save(captor.capture());
        assertThat(captor.getValue().getStartAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 12, 0));
        assertThat(captor.getValue().getEndAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 14, 0));
    }

    @Test
    void stage_fallsBackToNow_whenAllTimesNull() {
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(source(null, null)));
        when(timelineDraftEventSuggestionService.save(any())).thenReturn(savedSuggestion());

        service.stage(TASK_ID);

        ArgumentCaptor<TimelineDraftEventSuggestion> captor = ArgumentCaptor.forClass(TimelineDraftEventSuggestion.class);
        verify(timelineDraftEventSuggestionService).save(captor.capture());
        assertThat(captor.getValue().getStartAt()).isNotNull(); // 검증기의 startAt NOT NULL 요구 충족
        assertThat(captor.getValue().getEndAt()).isNull();
    }

    @Test
    void stage_nullsEndAt_whenMaxEndAtBeforeStartAt() {
        TimelineDraftSourceItem s1 = source(LocalDateTime.of(2000, 1, 2, 10, 0), null);
        TimelineDraftSourceItem s2 = source(null, LocalDateTime.of(2000, 1, 2, 9, 0));
        when(timelineDraftSourceItemService.findByTaskId(TASK_ID)).thenReturn(List.of(s1, s2));
        when(timelineDraftEventSuggestionService.save(any())).thenReturn(savedSuggestion());

        service.stage(TASK_ID);

        ArgumentCaptor<TimelineDraftEventSuggestion> captor = ArgumentCaptor.forClass(TimelineDraftEventSuggestion.class);
        verify(timelineDraftEventSuggestionService).save(captor.capture());
        assertThat(captor.getValue().getStartAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 10, 0));
        assertThat(captor.getValue().getEndAt()).isNull(); // endAt >= startAt 위반 방지
    }
}
