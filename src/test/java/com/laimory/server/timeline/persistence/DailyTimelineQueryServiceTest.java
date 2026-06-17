package com.laimory.server.timeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.DailyTimelineResult;
import com.laimory.server.timeline.dto.TimelineCardResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 조회 오케스트레이터가 record + 카드 + 아이템을 올바르게 조립하는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyTimelineQueryServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineCardService timelineCardService;
    @Mock
    private TimelineItemService timelineItemService;

    @InjectMocks
    private DailyTimelineQueryService queryService;

    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);

    @Test
    void getDailyTimeline_assemblesRecordCardsAndItems() {
        DailyRecord record = DailyRecord.createDraft(7L, RECORD_DATE);
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

        DailyTimelineResult result = queryService.getDailyTimeline(300L);

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

        assertThatThrownBy(() -> queryService.getDailyTimeline(999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
    }
}
