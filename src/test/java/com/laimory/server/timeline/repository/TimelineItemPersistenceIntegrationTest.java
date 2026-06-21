package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.ItemTypes;
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
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다(item_type 컬럼 포함).
 * - flush+clear로 1차 캐시를 비워 payload를 DB JSON에서 실제로 재역직렬화하게 한다.
 * - payload는 타입 정보 없는 raw JSON(JsonNode)이고, 타입은 item_type 컬럼이 권위다.
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

    // payload에 날짜 필드가 없으므로 기본 ObjectMapper로 충분하다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsAndReloadsTypedPayloadFromJsonColumn() throws Exception {
        DailyRecord record = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 8)));
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(record.getDailyRecordId(),
                        LocalDateTime.of(2026, 5, 8, 8, 30),
                        LocalDateTime.of(2026, 5, 8, 9, 10),
                        "출근길", "강남역 -> 성수역 · 7호선"));

        MovementPayload movement = new MovementPayload("강남역", "성수역", "SUBWAY", "7호선");
        TimelineItem saved = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(),
                        ItemTypes.typeOf(movement),
                        LocalDateTime.of(2026, 5, 8, 8, 30),
                        LocalDateTime.of(2026, 5, 8, 9, 10),
                        objectMapper.valueToTree(movement)));

        em.flush();
        em.clear();

        TimelineItem reloaded = timelineItemRepository.findById(saved.getTimelineItemId()).orElseThrow();
        assertThat(reloaded.getItemType()).isEqualTo(ItemType.MOVEMENT);
        assertThat(reloaded.getPayload().get("fromPlace").asText()).isEqualTo("강남역");
        assertThat(objectMapper.treeToValue(reloaded.getPayload(), MovementPayload.class)).isEqualTo(movement);
        assertThat(reloaded.getTimelineEventId()).isEqualTo(event.getTimelineEventId());
    }

    @Test
    void persistsAndReloadsAllPayloadSubtypes() throws Exception {
        DailyRecord record = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 9)));
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(record.getDailyRecordId(), LocalDateTime.of(2026, 5, 9, 12, 0), null, "하루", null));

        PhotoPayload photo = new PhotoPayload("content://media/external/images/media/12345", 37.5445, 127.0557);
        CalendarPayload calendar = new CalendarPayload("주간 회의", "회사", "회의실 A");
        LocationPayload location = new LocationPayload("작은 카페", "성수동", 37.5445, 127.0557);

        Long photoId = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(), ItemType.PHOTO,
                        LocalDateTime.of(2026, 5, 9, 12, 0), null, objectMapper.valueToTree(photo))).getTimelineItemId();
        Long calendarId = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(), ItemType.CALENDAR,
                        LocalDateTime.of(2026, 5, 9, 12, 1), null, objectMapper.valueToTree(calendar))).getTimelineItemId();
        Long locationId = timelineItemRepository.save(
                TimelineItem.of(event.getTimelineEventId(), ItemType.LOCATION,
                        LocalDateTime.of(2026, 5, 9, 12, 2), null, objectMapper.valueToTree(location))).getTimelineItemId();

        em.flush();
        em.clear();

        TimelineItem reloadedPhoto = timelineItemRepository.findById(photoId).orElseThrow();
        assertThat(reloadedPhoto.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat((TimelineItemPayload) objectMapper.treeToValue(reloadedPhoto.getPayload(), PhotoPayload.class))
                .isEqualTo(photo);

        TimelineItem reloadedCalendar = timelineItemRepository.findById(calendarId).orElseThrow();
        assertThat(reloadedCalendar.getItemType()).isEqualTo(ItemType.CALENDAR);
        assertThat((TimelineItemPayload) objectMapper.treeToValue(reloadedCalendar.getPayload(), CalendarPayload.class))
                .isEqualTo(calendar);

        TimelineItem reloadedLocation = timelineItemRepository.findById(locationId).orElseThrow();
        assertThat(reloadedLocation.getItemType()).isEqualTo(ItemType.LOCATION);
        assertThat((TimelineItemPayload) objectMapper.treeToValue(reloadedLocation.getPayload(), LocationPayload.class))
                .isEqualTo(location);
    }
}
