package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 삭제 DB 트랜잭션 bean ↔ 실 MySQL FK {@code ON DELETE CASCADE} 검증. JPA cascade가 없으므로
 * 하위 행 소멸은 DB FK가 담당한다는 계약을 실 DB로 고정한다(mockito론 못 잡음):
 * Event 삭제 시 Items 소멸 + DailyRecord 유지(마지막 Event여도), DailyRecord 삭제 시 전체 소멸.
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
        dailyRecordRepository.findByUserIdAndRecordDate(USER_ID, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
    }

    private Long saveEventWithItem(String title, int hour, String rawId) {
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(hour, 0), null, title, null));
        timelineItemRepository.save(TimelineItem.of(event.getTimelineEventId(), ItemType.CALENDAR, rawId,
                DATE.atTime(hour, 0), null, objectMapper.valueToTree(new CalendarPayload(title, null, null, false))));
        return event.getTimelineEventId();
    }

    @Test
    void deleteEvent_cascadesItems_andKeepsSiblingEventAndRecord() {
        Long targetEventId = saveEventWithItem("삭제 대상", 9, "raw-del-1");
        Long siblingEventId = saveEventWithItem("남는 이벤트", 10, "raw-keep-1");

        timelineDeletionTransactionService.deleteEvent(USER_ID, targetEventId);

        // 삭제 이벤트와 그 하위 item만 DB FK cascade로 소멸한다.
        assertThat(timelineEventRepository.findById(targetEventId)).isEmpty();
        assertThat(timelineItemRepository.findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(targetEventId))
                .isEmpty();
        // 형제 이벤트·item과 record는 유지된다.
        assertThat(timelineEventRepository.findById(siblingEventId)).isPresent();
        assertThat(timelineItemRepository.findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(siblingEventId))
                .hasSize(1);
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
    }

    @Test
    void deleteLastEvent_keepsDailyRecord() {
        Long onlyEventId = saveEventWithItem("마지막 이벤트", 9, "raw-last-1");

        timelineDeletionTransactionService.deleteEvent(USER_ID, onlyEventId);

        // 마지막 Event를 지워도 DailyRecord는 유지된다 — 하루 전체 제거는 DailyRecord 삭제 API만 담당.
        assertThat(timelineEventRepository.findById(onlyEventId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
        assertThat(timelineEventRepository.findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId)).isEmpty();
    }

    @Test
    void deleteDailyRecord_cascadesAllEventsAndItems() {
        Long event1 = saveEventWithItem("이벤트1", 9, "raw-all-1");
        Long event2 = saveEventWithItem("이벤트2", 10, "raw-all-2");

        timelineDeletionTransactionService.deleteDailyRecord(USER_ID, recordId);

        // record 한 건 삭제로 하위 events/items 전체가 DB FK cascade로 소멸한다.
        assertThat(dailyRecordRepository.findById(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(event1)).isEmpty();
        assertThat(timelineEventRepository.findById(event2)).isEmpty();
        assertThat(timelineItemRepository.findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(event1)).isEmpty();
        assertThat(timelineItemRepository.findByTimelineEventIdOrderByStartAtAscTimelineItemIdAsc(event2)).isEmpty();
    }
}
