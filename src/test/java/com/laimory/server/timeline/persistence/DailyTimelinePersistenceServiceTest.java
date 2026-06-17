package com.laimory.server.timeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.dto.CardProposalDto;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 영속 오케스트레이터가 3개 leaf 서비스를 올바르게 합성하는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyTimelinePersistenceServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineCardService timelineCardService;
    @Mock
    private TimelineItemService timelineItemService;

    @InjectMocks
    private DailyTimelinePersistenceService persistenceService;

    private static final Long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);

    @Test
    void persist_reusesExistingDraftRecord_withoutSavingRecord() {
        DailyRecord existing = DailyRecord.createDraft(USER_ID, RECORD_DATE);
        ReflectionTestUtils.setField(existing, "id", 100L);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(existing));
        stubCardSaveWithSequentialIds();

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, t, null, "summary-0", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardProposalDto> cards = List.of(
                new CardProposalDto("아침", "산책", t, t.plusHours(1), List.of(0)));

        Long result = persistenceService.persist(USER_ID, RECORD_DATE, sources, cards);

        assertThat(result).isEqualTo(100L);
        // 기존 DRAFT 재사용: record는 save 하지 않는다.
        verify(dailyRecordService, never()).save(any());
        verify(timelineCardService, times(1)).save(any());
        verify(timelineItemService, times(1)).save(any());
    }

    @Test
    void persist_createsDraftWhenAbsent_andMapsItemsToCorrectCardByItemId() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.empty());
        when(dailyRecordService.save(any())).thenAnswer(invocation -> {
            DailyRecord created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 200L);
            return created;
        });
        stubCardSaveWithSequentialIds();

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 8, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, t, null, "s0", new PhotoPayload("uri0", 1.0, 2.0)),
                new SourceItemDto(1, t.plusHours(1), null, "s1",
                        new LocationPayload("place", "area", 3.0, 4.0)),
                new SourceItemDto(2, t.plusHours(2), null, "s2", new PhotoPayload("uri2", 5.0, 6.0)));
        // 카드 A: item 0,2 / 카드 B: item 1
        List<CardProposalDto> cards = List.of(
                new CardProposalDto("A", "subA", t, t.plusHours(2), List.of(0, 2)),
                new CardProposalDto("B", "subB", t.plusHours(1), null, List.of(1)));

        Long result = persistenceService.persist(USER_ID, RECORD_DATE, sources, cards);

        assertThat(result).isEqualTo(200L);
        // 없을 때 DRAFT 생성된다.
        ArgumentCaptor<DailyRecord> recordCaptor = ArgumentCaptor.forClass(DailyRecord.class);
        verify(dailyRecordService).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(recordCaptor.getValue().getRecordDate()).isEqualTo(RECORD_DATE);

        verify(timelineCardService, times(2)).save(any());

        // item이 올바른 카드(첫 카드 id=1, 둘째 카드 id=2)로 매핑되는지 확인.
        ArgumentCaptor<TimelineItem> itemCaptor = ArgumentCaptor.forClass(TimelineItem.class);
        verify(timelineItemService, times(3)).save(itemCaptor.capture());
        List<TimelineItem> savedItems = itemCaptor.getAllValues();
        // 첫 카드(id=1)에 source 0,2 / 둘째 카드(id=2)에 source 1
        assertThat(savedItems.get(0).getTimelineCardId()).isEqualTo(1L);
        assertThat(savedItems.get(0).getStartAt()).isEqualTo(t);
        assertThat(savedItems.get(1).getTimelineCardId()).isEqualTo(1L);
        assertThat(savedItems.get(1).getStartAt()).isEqualTo(t.plusHours(2));
        assertThat(savedItems.get(2).getTimelineCardId()).isEqualTo(2L);
        assertThat(savedItems.get(2).getStartAt()).isEqualTo(t.plusHours(1));
    }

    /** 저장되는 카드마다 1부터 증가하는 id를 부여해 반환한다(item save가 card id를 참조할 수 있도록). */
    private void stubCardSaveWithSequentialIds() {
        when(timelineCardService.save(any())).thenAnswer(new org.mockito.stubbing.Answer<TimelineCard>() {
            private long nextId = 1L;

            @Override
            public TimelineCard answer(org.mockito.invocation.InvocationOnMock invocation) {
                TimelineCard card = invocation.getArgument(0);
                ReflectionTestUtils.setField(card, "id", nextId++);
                return card;
            }
        });
    }
}
