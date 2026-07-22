package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hibernate @JdbcTypeCode(JSON) ↔ MySQL 실 왕복 + N:M junction 매핑 검증("구현 1순위 스모크 테스트").
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다(junction composite PK 포함).
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

    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;

    @PersistenceContext
    private EntityManager em;

    // payload에 날짜 필드가 없으므로 기본 ObjectMapper로 충분하다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsAndReloadsTypedPayloadFromJsonColumn_withJunctionLink() throws Exception {
        DailyRecord record = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 8), LocalDateTime.of(2026, 5, 8, 12, 0), "Asia/Seoul"));
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(record.getDailyRecordId(), TimelineEventType.MOVEMENT,
                        LocalDateTime.of(2026, 5, 8, 8, 30),
                        LocalDateTime.of(2026, 5, 8, 9, 10),
                        "출근길", "강남역 -> 성수역 · 7호선"));

        MovementPayload movement = new MovementPayload(
                new MovementEndpoint(37.4979, 127.0276, null, null),
                new MovementEndpoint(37.5445, 127.0557, null, null),
                "IN_VEHICLE", null);
        TimelineItem saved = timelineItemRepository.save(
                TimelineItem.of(ItemType.MOVEMENT,
                        "0197b1c2-0000-7000-8000-000000000002",
                        LocalDateTime.of(2026, 5, 8, 8, 30),
                        LocalDateTime.of(2026, 5, 8, 9, 10),
                        objectMapper.valueToTree(movement)));
        timelineEventItemRepository.save(TimelineEventItem.of(event.getTimelineEventId(), saved.getTimelineItemId()));

        em.flush();
        em.clear();

        TimelineItem reloaded = timelineItemRepository.findById(saved.getTimelineItemId()).orElseThrow();
        assertThat(reloaded.getItemType()).isEqualTo(ItemType.MOVEMENT);
        assertThat(reloaded.getRawId()).isEqualTo("0197b1c2-0000-7000-8000-000000000002");
        assertThat(reloaded.getPayload().get("start").get("latitude").asDouble()).isEqualTo(37.4979);
        assertThat(objectMapper.treeToValue(reloaded.getPayload(), MovementPayload.class)).isEqualTo(movement);

        // junction(composite PK) 왕복 — Item은 event FK 없이 junction으로만 연결된다.
        List<TimelineEventItem> links = timelineEventItemRepository.findByTimelineEventId(event.getTimelineEventId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getTimelineItemId()).isEqualTo(saved.getTimelineItemId());

        // 대표 non-default eventType이 @Enumerated(STRING)으로 왕복된다.
        TimelineEvent reloadedEvent = timelineEventRepository.findById(event.getTimelineEventId()).orElseThrow();
        assertThat(reloadedEvent.getEventType()).isEqualTo(TimelineEventType.MOVEMENT);
    }

    @Test
    void persistsAndReloadsAllPayloadSubtypes() throws Exception {
        PhotoPayload photo = new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg",
                "content://media/external/images/media/12345", 37.5445, 127.0557, "사진 설명",
                "https://cdn.example/hash/photos/0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
        CalendarPayload calendar = new CalendarPayload("주간 회의", "회의실 A", "설명", false);
        StayPayload stay = new StayPayload(37.5445, 127.0557,
                "서울 성동구 왕십리로 83-21", List.of("성수낙낙", "작은 카페"), "1시간45분");

        Long photoId = timelineItemRepository.save(
                TimelineItem.of(ItemType.PHOTO, "raw-photo",
                        LocalDateTime.of(2026, 5, 9, 12, 0), null, objectMapper.valueToTree(photo))).getTimelineItemId();
        Long calendarId = timelineItemRepository.save(
                TimelineItem.of(ItemType.CALENDAR, "raw-calendar",
                        LocalDateTime.of(2026, 5, 9, 12, 1), null, objectMapper.valueToTree(calendar))).getTimelineItemId();
        Long stayId = timelineItemRepository.save(
                TimelineItem.of(ItemType.STAY, "raw-stay",
                        LocalDateTime.of(2026, 5, 9, 12, 2), null, objectMapper.valueToTree(stay))).getTimelineItemId();

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

        TimelineItem reloadedStay = timelineItemRepository.findById(stayId).orElseThrow();
        assertThat(reloadedStay.getItemType()).isEqualTo(ItemType.STAY);
        assertThat((TimelineItemPayload) objectMapper.treeToValue(reloadedStay.getPayload(), StayPayload.class))
                .isEqualTo(stay);
    }

    @Test
    void junction_rejectsDuplicatePair_byCompositePrimaryKey() {
        // 같은 (event, item) pair 중복 연결은 composite PK가 DB에서 거부한다(N:M 무결성의 유일한 DB 제약).
        DailyRecord record = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 11),
                LocalDateTime.of(2026, 5, 11, 12, 0), "Asia/Seoul"));
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(record.getDailyRecordId(), TimelineEventType.UNKNOWN,
                        LocalDateTime.of(2026, 5, 11, 9, 0), null, "하루", null));
        TimelineItem item = timelineItemRepository.save(TimelineItem.of(ItemType.CALENDAR, "raw-dup",
                LocalDateTime.of(2026, 5, 11, 9, 0), null,
                objectMapper.valueToTree(new CalendarPayload("t", null, null, false))));
        timelineEventItemRepository.save(TimelineEventItem.of(event.getTimelineEventId(), item.getTimelineItemId()));
        em.flush();
        em.clear();

        // 새 영속성 컨텍스트에서 같은 pair를 다시 INSERT — merge가 아닌 raw INSERT 경로를 강제한다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            em.createNativeQuery("INSERT INTO timeline_event_items (timeline_event_id, timeline_item_id) VALUES (?1, ?2)")
                    .setParameter(1, event.getTimelineEventId())
                    .setParameter(2, item.getTimelineItemId())
                    .executeUpdate();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    void findRawIdsByTimelineItemIdInAndRawIdIn_returnsOnlySavedRawIdsAmongCandidates() {
        // append rawId 필터가 쓰는 projection 쿼리 — 메서드명/JPQL 정합을 실 DB로 검증(mockito론 못 잡음).
        TimelineItem itemA = timelineItemRepository.save(TimelineItem.of(ItemType.PHOTO, "raw-a",
                LocalDateTime.of(2026, 5, 10, 9, 0), null, objectMapper.valueToTree(
                        new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://a", 1.0, 2.0, null, null))));
        TimelineItem itemB = timelineItemRepository.save(TimelineItem.of(ItemType.PHOTO, "raw-b",
                LocalDateTime.of(2026, 5, 10, 10, 0), null, objectMapper.valueToTree(
                        new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://b", 1.0, 2.0, null, null))));
        em.flush();
        em.clear();

        // 후보 itemIds에 A만 포함 + 후보 rawIds [raw-a, raw-c] → 저장된 raw-a만 반환(raw-b는 itemIds 밖, raw-c는 미저장).
        List<String> found = timelineItemRepository.findRawIdsByTimelineItemIdInAndRawIdIn(
                List.of(itemA.getTimelineItemId()), List.of("raw-a", "raw-c"));

        assertThat(found).containsExactly("raw-a");
    }
}
