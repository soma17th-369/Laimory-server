package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 삭제 DB 트랜잭션 bean ↔ 실 MySQL N:M cascade/orphan 계약 검증(mockito론 못 잡음):
 * Event/Record 행 삭제 시 자기 junction은 DB FK {@code ON DELETE CASCADE}로 소멸하고, Item은 record FK가
 * 없으므로 삭제 대상에만 연결된 orphan을 명시 삭제하며 shared Item은 유지된다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineDeletionCascadeIntegrationTest {

    // 다른 통합 테스트의 고정 날짜(2000-01-01 콜백, 2000-01-02 편집 경합)와 (user_id, record_date) 유니크 충돌을 피한다.
    private static final LocalDate DATE = LocalDate.of(2000, 1, 3);
    private static final String ZONE = "Asia/Seoul";
    private static final long USER_ID = 0L;

    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private TimelineDeletionTransactionService timelineDeletionTransactionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long recordId;

    @BeforeEach
    void setUp() {
        deleteFixtureRecord();
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
    }

    @AfterEach
    void cleanUp() {
        deleteFixtureRecord();
    }

    private void deleteFixtureRecord() {
        // Item은 record cascade 대상이 아니므로 junction 경유로 수집해 함께 지운다(테스트 잔존 방지).
        dailyRecordRepository.findByUserIdAndRecordDate(USER_ID, DATE).ifPresent(record -> {
            List<Long> eventIds = timelineEventRepository
                    .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()).stream()
                    .map(TimelineEvent::getTimelineEventId)
                    .toList();
            List<Long> itemIds = eventIds.isEmpty() ? List.of()
                    : timelineEventItemRepository.findByTimelineEventIdIn(eventIds).stream()
                            .map(TimelineEventItem::getTimelineItemId)
                            .distinct()
                            .toList();
            dailyRecordRepository.deleteById(record.getDailyRecordId());
            if (!itemIds.isEmpty()) {
                timelineItemRepository.deleteAllByIdInBatch(itemIds);
            }
        });
    }

    private Long saveEvent(String title, int hour) {
        return timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(hour, 0), null, title, null))
                .getTimelineEventId();
    }

    private Long saveItemLinkedTo(String rawId, int hour, Long... eventIds) {
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(ItemType.CALENDAR, rawId,
                DATE.atTime(hour, 0), null, objectMapper.valueToTree(new CalendarPayload(rawId, null, null, false))));
        for (Long eventId : eventIds) {
            timelineEventItemRepository.save(TimelineEventItem.of(eventId, item.getTimelineItemId()));
        }
        return item.getTimelineItemId();
    }

    @Test
    void deleteEvent_deletesOrphanItem_keepsSharedItemAndSibling() {
        Long targetEventId = saveEvent("삭제 대상", 9);
        Long siblingEventId = saveEvent("남는 이벤트", 10);
        Long exclusiveItemId = saveItemLinkedTo("raw-exclusive", 9, targetEventId);
        Long sharedItemId = saveItemLinkedTo("raw-shared", 9, targetEventId, siblingEventId);

        timelineDeletionTransactionService.deleteEvent(USER_ID, targetEventId);

        // 삭제 이벤트의 junction은 DB FK cascade로, exclusive Item은 명시 삭제로 소멸한다.
        assertThat(timelineEventRepository.findById(targetEventId)).isEmpty();
        assertThat(timelineEventItemRepository.findByTimelineEventId(targetEventId)).isEmpty();
        assertThat(timelineItemRepository.findById(exclusiveItemId)).isEmpty();
        // shared Item과 형제 이벤트·그 연결·record는 유지된다.
        assertThat(timelineItemRepository.findById(sharedItemId)).isPresent();
        assertThat(timelineEventRepository.findById(siblingEventId)).isPresent();
        assertThat(timelineEventItemRepository.findByTimelineEventId(siblingEventId))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(sharedItemId);
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
    }

    @Test
    void deleteLastEvent_keepsDailyRecord() {
        Long onlyEventId = saveEvent("마지막 이벤트", 9);
        Long itemId = saveItemLinkedTo("raw-last", 9, onlyEventId);

        timelineDeletionTransactionService.deleteEvent(USER_ID, onlyEventId);

        // 마지막 Event를 지워도 DailyRecord는 유지된다 — 하루 전체 제거는 DailyRecord 삭제 API만 담당.
        assertThat(timelineEventRepository.findById(onlyEventId)).isEmpty();
        assertThat(timelineItemRepository.findById(itemId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
        assertThat(timelineEventRepository.findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId)).isEmpty();
    }

    @Test
    void deleteDailyRecord_cascadesEventsAndJunction_deletesOrphanItems() {
        Long event1 = saveEvent("이벤트1", 9);
        Long event2 = saveEvent("이벤트2", 10);
        Long item1 = saveItemLinkedTo("raw-all-1", 9, event1);
        // record 안 두 event가 공유하는 Item — record 전체 삭제라 함께 소멸해야 한다.
        Long item2 = saveItemLinkedTo("raw-all-2", 10, event1, event2);

        timelineDeletionTransactionService.deleteDailyRecord(USER_ID, recordId);

        // record 한 건 삭제로 하위 events/junction이 DB FK cascade로 소멸하고 orphan Item은 명시 삭제된다.
        assertThat(dailyRecordRepository.findById(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(event1)).isEmpty();
        assertThat(timelineEventRepository.findById(event2)).isEmpty();
        assertThat(timelineItemRepository.findById(item1)).isEmpty();
        assertThat(timelineItemRepository.findById(item2)).isEmpty();
        assertThat(timelineEventItemRepository.findByTimelineEventIdIn(List.of(event1, event2))).isEmpty();
    }
}
