package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** leaf 서비스가 Event bulk 조회를 자신의 레포로 위임하고 빈 IN 쿼리를 막는지 단위 검증. */
@ExtendWith(MockitoExtension.class)
class TimelineEventServiceTest {

    @Mock
    private TimelineEventRepository timelineEventRepository;

    @InjectMocks
    private TimelineEventService timelineEventService;

    @Test
    void findByDailyRecordIds_delegatesToOrderedRepositoryQuery() {
        List<Long> dailyRecordIds = List.of(10L, 20L);
        TimelineEvent event = TimelineEvent.of(10L, TimelineEventType.UNKNOWN,
                LocalDateTime.of(2026, 5, 8, 9, 0), null, "아침", null);
        List<TimelineEvent> events = List.of(event);
        when(timelineEventRepository
                .findByDailyRecordIdInOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(dailyRecordIds))
                .thenReturn(events);

        List<TimelineEvent> result = timelineEventService.findByDailyRecordIds(dailyRecordIds);

        assertThat(result).isSameAs(events);
        verify(timelineEventRepository)
                .findByDailyRecordIdInOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(dailyRecordIds);
    }

    @Test
    void findByDailyRecordIds_returnsEmptyWithoutRepositoryCall_whenIdsAreEmpty() {
        assertThat(timelineEventService.findByDailyRecordIds(List.of())).isEmpty();

        verifyNoInteractions(timelineEventRepository);
    }
}
