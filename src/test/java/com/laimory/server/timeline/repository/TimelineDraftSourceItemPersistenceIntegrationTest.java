package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * timeline_draft_source_items의 Hibernate @JdbcTypeCode(JSON) ↔ MySQL 실 왕복 검증.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다(audit/item_type 컬럼 포함).
 * - flush+clear로 1차 캐시를 비워 payload를 DB JSON에서 실제로 재역직렬화하게 한다.
 * - cleanup이 쓰는 findByCreatedAtBefore의 strict {@code <} 경계와 단일 행 삭제도 실 쿼리로 고정한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TimelineDraftSourceItemPersistenceIntegrationTest {

    @Autowired
    private TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;
    @Autowired
    private TimelineDraftSourceItemBatchRepository timelineDraftSourceItemBatchRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsAndReloadsDraftSourceItemFromJsonColumn() throws Exception {
        String taskId = "11111111-1111-1111-1111-111111111111";
        MovementPayload movement = new MovementPayload(
                new MovementEndpoint(37.4979, 127.0276, null, null),
                new MovementEndpoint(37.5445, 127.0557, "서울 성동구 뚝섬로 지하 342", List.of("성수역 2호선")),
                "IN_VEHICLE", 5200.0);

        TimelineDraftSourceItem toSave = TimelineDraftSourceItem.of(
                taskId,
                0L,
                ItemType.MOVEMENT,
                "0197b1c2-0000-7000-8000-000000000001",
                LocalDateTime.of(2026, 5, 8, 8, 30),
                LocalDateTime.of(2026, 5, 8, 9, 10),
                objectMapper.valueToTree(movement));
        TimelineDraftSourceItem saved = timelineDraftSourceItemRepository.save(toSave);

        em.flush();
        em.clear();

        TimelineDraftSourceItem reloaded =
                timelineDraftSourceItemRepository.findById(saved.getTimelineDraftSourceItemId()).orElseThrow();
        assertThat(reloaded.getTaskId()).isEqualTo(taskId);
        assertThat(reloaded.getUserId()).isEqualTo(0L);
        assertThat(reloaded.getItemType()).isEqualTo(ItemType.MOVEMENT);
        assertThat(reloaded.getRawId()).isEqualTo("0197b1c2-0000-7000-8000-000000000001");
        assertThat(reloaded.getStartAt()).isEqualTo(LocalDateTime.of(2026, 5, 8, 8, 30));
        assertThat(reloaded.getEndAt()).isEqualTo(LocalDateTime.of(2026, 5, 8, 9, 10));
        assertThat(reloaded.getPayload().get("end").get("address").asText()).isEqualTo("서울 성동구 뚝섬로 지하 342");
        assertThat(objectMapper.treeToValue(reloaded.getPayload(), MovementPayload.class)).isEqualTo(movement);

        // audit 컬럼이 채워졌는지 확인
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getModifiedBy()).isNull();
    }

    @Test
    void jdbcBatchPayloadAndAuditMatchJpaRepresentation() {
        String jpaTaskId = "44444444-4444-4444-4444-444444444444";
        String batchTaskId = "55555555-5555-5555-5555-555555555555";
        var payload = objectMapper.createObjectNode();
        payload.put("title", "한글과 \\\"따옴표\\\"")
                .put("count", 7);
        payload.putArray("tags")
                .add("첫째")
                .add("둘째");

        TimelineDraftSourceItem jpaSaved = timelineDraftSourceItemRepository.saveAndFlush(TimelineDraftSourceItem.of(
                jpaTaskId, 0L, ItemType.CALENDAR, "raw-jpa",
                LocalDateTime.of(2026, 5, 8, 9, 0), null, payload));
        timelineDraftSourceItemBatchRepository.insertAll(List.of(TimelineDraftSourceItem.of(
                batchTaskId, 0L, ItemType.CALENDAR, "raw-batch",
                LocalDateTime.of(2026, 5, 8, 9, 0), null, payload)));

        String jpaJson = storedPayload(jpaTaskId);
        String batchJson = storedPayload(batchTaskId);
        assertThat(batchJson).isEqualTo(jpaJson);

        TimelineDraftSourceItem batchSaved = timelineDraftSourceItemRepository.findByTaskId(batchTaskId).getFirst();
        assertThat(Duration.between(jpaSaved.getCreatedAt(), batchSaved.getCreatedAt()).abs())
                .isLessThan(Duration.ofMinutes(1));
        assertThat(batchSaved.getUpdatedAt()).isEqualTo(batchSaved.getCreatedAt());
    }

    @Test
    void uniqueTaskRawId_isCaseSensitive_matchingJavaDedupe() {
        // raw_id가 utf8mb4_bin이라 (task, "abc")와 (task, "ABC")는 서로 다른 rawId로 취급된다 —
        // 서버 Java dedupe(대소문자 구분)와 규칙이 일치한다. _unicode_ci였다면 여기서 duplicate-key 500이 났다.
        String taskId = "22222222-2222-2222-2222-222222222222";
        timelineDraftSourceItemRepository.save(sourceItem(taskId, "abc"));
        timelineDraftSourceItemRepository.save(sourceItem(taskId, "ABC"));

        em.flush();
        em.clear();

        assertThat(timelineDraftSourceItemRepository.findByTaskId(taskId))
                .extracting(TimelineDraftSourceItem::getRawId)
                .containsExactlyInAnyOrder("abc", "ABC");
    }

    @Test
    void findCreatedBefore_isStrictlyBeforeCutoff_andDeleteByIdRemovesOnlyThatRow() {
        String taskId = "33333333-3333-3333-3333-333333333333";
        TimelineDraftSourceItem before = timelineDraftSourceItemRepository.save(sourceItem(taskId, "raw-before"));
        TimelineDraftSourceItem exact = timelineDraftSourceItemRepository.save(sourceItem(taskId, "raw-exact"));
        TimelineDraftSourceItem after = timelineDraftSourceItemRepository.save(sourceItem(taskId, "raw-after"));
        em.flush();

        // created_at은 @CreatedDate(updatable=false)라 JPA로 못 바꾼다 — native update로 경계 3점을 고정한다.
        LocalDateTime cutoff = LocalDateTime.of(2026, 5, 15, 4, 0);
        backdateCreatedAt(before.getTimelineDraftSourceItemId(), cutoff.minusSeconds(1));
        backdateCreatedAt(exact.getTimelineDraftSourceItemId(), cutoff);
        backdateCreatedAt(after.getTimelineDraftSourceItemId(), cutoff.plusSeconds(1));
        em.clear();

        // strict < : 정확히 cutoff인 행은 아직 만료가 아니다. 공유 로컬 DB의 무관한 행은 taskId로 걸러 판정한다.
        List<Long> expiredIds = timelineDraftSourceItemRepository.findByCreatedAtBefore(cutoff).stream()
                .filter(row -> taskId.equals(row.getTaskId()))
                .map(TimelineDraftSourceItem::getTimelineDraftSourceItemId)
                .toList();
        assertThat(expiredIds).containsExactly(before.getTimelineDraftSourceItemId());

        // cleanup은 S3 삭제에 성공한 행만 PK로 지운다 — 지정한 한 행 외에는 남아야 한다.
        timelineDraftSourceItemRepository.deleteById(before.getTimelineDraftSourceItemId());
        em.flush();
        em.clear();

        assertThat(timelineDraftSourceItemRepository.findByTaskId(taskId))
                .extracting(TimelineDraftSourceItem::getRawId)
                .containsExactlyInAnyOrder("raw-exact", "raw-after");
    }

    private void backdateCreatedAt(Long timelineDraftSourceItemId, LocalDateTime createdAt) {
        em.createNativeQuery("UPDATE timeline_draft_source_items SET created_at = :createdAt "
                        + "WHERE timeline_draft_source_item_id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", timelineDraftSourceItemId)
                .executeUpdate();
    }

    private String storedPayload(String taskId) {
        return jdbcTemplate.queryForObject(
                "select cast(payload as char) from timeline_draft_source_items where task_id = ?",
                String.class,
                taskId);
    }

    private TimelineDraftSourceItem sourceItem(String taskId, String rawId) {
        return TimelineDraftSourceItem.of(taskId, 0L, ItemType.CALENDAR, rawId,
                LocalDateTime.of(2026, 5, 8, 9, 0), null,
                objectMapper.valueToTree(new CalendarPayload("회의", null, null, false)));
    }
}
