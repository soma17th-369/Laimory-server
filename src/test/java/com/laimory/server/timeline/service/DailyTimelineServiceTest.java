package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineCardResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineCard;
import com.laimory.server.timeline.entity.TimelineItem;
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

/** 하루 타임라인 오케스트레이터가 3개 leaf 서비스를 올바르게 합성하는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyTimelineServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineCardService timelineCardService;
    @Mock
    private TimelineItemService timelineItemService;

    @InjectMocks
    private DailyTimelineService dailyTimelineService;

    private static final Long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);

    // --- persist (쓰기) ---

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
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(0)));

        Long result = dailyTimelineService.persist(USER_ID, RECORD_DATE, sources, cards);

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
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("A", "subA", t, t.plusHours(2), List.of(0, 2)),
                new CardSuggestionDto("B", "subB", t.plusHours(1), null, List.of(1)));

        Long result = dailyTimelineService.persist(USER_ID, RECORD_DATE, sources, cards);

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
        assertThat(savedItems.get(0).getTimelineCardId()).isEqualTo(1L);
        assertThat(savedItems.get(0).getStartAt()).isEqualTo(t);
        assertThat(savedItems.get(1).getTimelineCardId()).isEqualTo(1L);
        assertThat(savedItems.get(1).getStartAt()).isEqualTo(t.plusHours(2));
        assertThat(savedItems.get(2).getTimelineCardId()).isEqualTo(2L);
        assertThat(savedItems.get(2).getStartAt()).isEqualTo(t.plusHours(1));
    }

    // --- getDailyTimeline (읽기) ---

    @Test
    void getDailyTimeline_assemblesRecordCardsAndItems() {
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE);
        ReflectionTestUtils.setField(record, "id", 300L);
        ReflectionTestUtils.setField(record, "emotionType", EmotionType.HAPPY);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineCard card = TimelineCard.of(300L, t, t.plusHours(2), "제목", "부제목");
        ReflectionTestUtils.setField(card, "id", 11L);
        ReflectionTestUtils.setField(card, "memo", "내 메모");
        when(timelineCardService.findByDailyRecordId(300L)).thenReturn(List.of(card));

        PhotoPayload photo = new PhotoPayload("uri", 1.0, 2.0);
        LocationPayload location = new LocationPayload("카페", "강남", 3.0, 4.0);
        TimelineItem item0 = TimelineItem.of(11L, t, null, photo);
        ReflectionTestUtils.setField(item0, "id", 21L);
        TimelineItem item1 = TimelineItem.of(11L, t.plusHours(1), t.plusHours(2), location);
        ReflectionTestUtils.setField(item1, "id", 22L);
        when(timelineItemService.findByTimelineCardId(11L)).thenReturn(List.of(item0, item1));

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(300L);

        assertThat(result.recordDate()).isEqualTo(RECORD_DATE);
        assertThat(result.emotionType()).isEqualTo(EmotionType.HAPPY);
        assertThat(result.cards()).hasSize(1);

        TimelineCardResponse cardResponse = result.cards().get(0);
        assertThat(cardResponse.id()).isEqualTo(11L);
        assertThat(cardResponse.startAt()).isEqualTo(t);
        assertThat(cardResponse.endAt()).isEqualTo(t.plusHours(2));
        assertThat(cardResponse.title()).isEqualTo("제목");
        assertThat(cardResponse.subtitle()).isEqualTo("부제목");
        assertThat(cardResponse.memo()).isEqualTo("내 메모");
        assertThat(cardResponse.items()).hasSize(2);

        TimelineItemResponse itemResponse0 = cardResponse.items().get(0);
        assertThat(itemResponse0.id()).isEqualTo(21L);
        assertThat(itemResponse0.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(itemResponse0.startAt()).isEqualTo(t);
        assertThat(itemResponse0.endAt()).isNull();
        assertThat(itemResponse0.payload()).isSameAs(photo);

        TimelineItemResponse itemResponse1 = cardResponse.items().get(1);
        assertThat(itemResponse1.id()).isEqualTo(22L);
        assertThat(itemResponse1.itemType()).isEqualTo(ItemType.LOCATION);
        assertThat(itemResponse1.payload()).isSameAs(location);
    }

    @Test
    void getDailyTimeline_throwsWhenRecordNotFound() {
        when(dailyRecordService.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getDailyTimeline(999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
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
