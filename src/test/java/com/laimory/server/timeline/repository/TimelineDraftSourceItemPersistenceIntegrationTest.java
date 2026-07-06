package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * timeline_draft_source_items의 Hibernate @JdbcTypeCode(JSON) ↔ MySQL 실 왕복 검증.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다(audit/item_type 컬럼 포함).
 * - flush+clear로 1차 캐시를 비워 payload를 DB JSON에서 실제로 재역직렬화하게 한다.
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
                LocalDateTime.of(2026, 5, 8, 8, 30),
                LocalDateTime.of(2026, 5, 8, 9, 10),
                objectMapper.valueToTree(movement));
        toSave.assignEventSuggestion(555L);   // 신규 soft-ref 컬럼 왕복 검증
        TimelineDraftSourceItem saved = timelineDraftSourceItemRepository.save(toSave);

        em.flush();
        em.clear();

        TimelineDraftSourceItem reloaded =
                timelineDraftSourceItemRepository.findById(saved.getTimelineDraftSourceItemId()).orElseThrow();
        assertThat(reloaded.getTaskId()).isEqualTo(taskId);
        assertThat(reloaded.getUserId()).isEqualTo(0L);
        assertThat(reloaded.getItemType()).isEqualTo(ItemType.MOVEMENT);
        assertThat(reloaded.getStartAt()).isEqualTo(LocalDateTime.of(2026, 5, 8, 8, 30));
        assertThat(reloaded.getEndAt()).isEqualTo(LocalDateTime.of(2026, 5, 8, 9, 10));
        assertThat(reloaded.getTimelineDraftEventSuggestionId()).isEqualTo(555L);
        assertThat(reloaded.getPayload().get("end").get("address").asText()).isEqualTo("서울 성동구 뚝섬로 지하 342");
        assertThat(objectMapper.treeToValue(reloaded.getPayload(), MovementPayload.class)).isEqualTo(movement);

        // audit 컬럼이 채워졌는지 확인
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getModifiedBy()).isNull();
    }
}
