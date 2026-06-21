package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate @JdbcTypeCode(JSON) ↔ MySQL 실 왕복 검증("구현 1순위 스모크 테스트").
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다.
 * - flush+clear로 1차 캐시를 비워 payload를 DB JSON에서 실제로 재역직렬화하게 한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TimelineItemPersistenceIntegrationTest {

    @Autowired
    private DailyRecordRepository dailyRecordRepository;

    @Autowired
    private TimelineEventRepository timelineEventRepository;

    @Autowired
    private TimelineItemRepository timelineItemRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void persistsAndReloadsTypedPayloadFromJsonColumn() {
        DailyRecord record = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 8)));
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(record.getDailyRecordId(),
                        LocalDateTime.of(2026, 5, 8, 8, 30),
                        LocalDateTime.of(2026, 5, 8, 9, 10),
                        "출근길", "강남역 -> 성수역 · 7호선"));

        TimelineItemPayload movement = new MovementPayload("강남역", "성수역", "SUBWAY", "7호선");
        TimelineItem saved = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(),
                        LocalDateTime.of(2026, 5, 8, 8, 30),
                        LocalDateTime.of(2026, 5, 8, 9, 10),
                        movement));

        em.flush();
        em.clear();

        TimelineItem reloaded = timelineItemRepository.findById(saved.getTimelineItemId()).orElseThrow();
        assertThat(reloaded.getPayload())
                .isInstanceOf(MovementPayload.class)
                .isEqualTo(movement);
        assertThat(reloaded.itemType()).isEqualTo(movement.itemType());
        assertThat(reloaded.getTimelineEventId()).isEqualTo(event.getTimelineEventId());
    }

    @Test
    void persistsAndReloadsAllPayloadSubtypes() {
        DailyRecord record = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 9)));
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(record.getDailyRecordId(), LocalDateTime.of(2026, 5, 9, 12, 0), null, "하루", null));

        TimelineItemPayload photo = new PhotoPayload("content://media/external/images/media/12345", 37.5445, 127.0557);
        TimelineItemPayload calendar = new CalendarPayload("주간 회의", "회사", "회의실 A");
        TimelineItemPayload location = new LocationPayload("작은 카페", "성수동", 37.5445, 127.0557);

        Long photoId = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(), LocalDateTime.of(2026, 5, 9, 12, 0), null, photo)).getTimelineItemId();
        Long calendarId = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(), LocalDateTime.of(2026, 5, 9, 12, 1), null, calendar)).getTimelineItemId();
        Long locationId = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(), LocalDateTime.of(2026, 5, 9, 12, 2), null, location)).getTimelineItemId();

        em.flush();
        em.clear();

        assertThat(timelineItemRepository.findById(photoId).orElseThrow().getPayload())
                .isInstanceOf(PhotoPayload.class).isEqualTo(photo);
        assertThat(timelineItemRepository.findById(calendarId).orElseThrow().getPayload())
                .isInstanceOf(CalendarPayload.class).isEqualTo(calendar);
        assertThat(timelineItemRepository.findById(locationId).orElseThrow().getPayload())
                .isInstanceOf(LocationPayload.class).isEqualTo(location);
    }
}
