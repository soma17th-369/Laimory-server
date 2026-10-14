package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * 계정 삭제의 콘텐츠 graph 제거 계약(#302).
 *
 * <p>여기서 고정하는 것은 <b>순서와 중단 조건</b>이다 — Item을 record보다 먼저 지워야 하고(record가
 * 먼저 사라지면 junction도 함께 사라져 Item을 특정할 수 없다), 다른 subject 소유가 섞이면 멈춰야 한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineContentErasureServiceTest {

    private static final UUID SUBJECT_ID = UUID.randomUUID();
    private static final UUID OTHER_SUBJECT_ID = UUID.randomUUID();
    private static final int BATCH = 200;

    @Mock
    private DailyRecordRepository dailyRecordRepository;
    @Mock
    private TimelineEventItemRepository timelineEventItemRepository;
    @Mock
    private TimelineItemRepository timelineItemRepository;
    @Mock
    private TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;

    @InjectMocks
    private TimelineContentErasureService service;

    @Test
    void record보다_Item을_먼저_지운다() {
        List<Long> recordIds = List.of(1L, 2L);
        when(dailyRecordRepository.findIdsBySubjectIdAfterId(any(), any(), any(Pageable.class)))
                .thenReturn(recordIds);
        when(timelineEventItemRepository.findItemIdsByDailyRecordIdIn(recordIds))
                .thenReturn(List.of(10L, 11L));
        when(timelineEventItemRepository.findOwnerSubjectIdsByItemIdIn(List.of(10L, 11L)))
                .thenReturn(List.of(SUBJECT_ID.toString()));
        when(dailyRecordRepository.deleteAllByIdIn(recordIds)).thenReturn(2);

        assertThat(service.deleteRecordBatch(SUBJECT_ID, BATCH)).isEqualTo(2);

        InOrder order = inOrder(timelineItemRepository, dailyRecordRepository);
        order.verify(timelineItemRepository).deleteAllByIdIn(List.of(10L, 11L));
        order.verify(dailyRecordRepository).deleteAllByIdIn(recordIds);
    }

    @Test
    void 다른_subject가_소유한_Item이_섞이면_아무것도_지우지_않는다() {
        List<Long> recordIds = List.of(1L);
        when(dailyRecordRepository.findIdsBySubjectIdAfterId(any(), any(), any(Pageable.class)))
                .thenReturn(recordIds);
        when(timelineEventItemRepository.findItemIdsByDailyRecordIdIn(recordIds)).thenReturn(List.of(10L));
        when(timelineEventItemRepository.findOwnerSubjectIdsByItemIdIn(List.of(10L)))
                .thenReturn(List.of(SUBJECT_ID.toString(), OTHER_SUBJECT_ID.toString()));

        assertThatThrownBy(() -> service.deleteRecordBatch(SUBJECT_ID, BATCH))
                .isInstanceOf(TimelineContentErasureService.CrossSubjectItemException.class);

        verify(timelineItemRepository, never()).deleteAllByIdIn(any());
        verify(dailyRecordRepository, never()).deleteAllByIdIn(any());
    }

    @Test
    void 대상이_없으면_0을_돌려준다() {
        when(dailyRecordRepository.findIdsBySubjectIdAfterId(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.deleteRecordBatch(SUBJECT_ID, BATCH)).isZero();
    }
}
