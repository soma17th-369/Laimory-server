package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * timeline_draft_event_suggestions ↔ MySQL 실 왕복 검증.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합(신규 테이블 포함)을 검증한다.
 * - AI는 감사 컬럼을 모른 채 raw INSERT하므로, created_at/updated_at을 생략한 native INSERT에서
 *   DB default(CURRENT_TIMESTAMP)가 채워지는지 검증한다.
 * - event_type을 생략한 구버전 writer INSERT는 DB default 'UNKNOWN'으로 로드돼야 한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TimelineDraftEventSuggestionPersistenceIntegrationTest {

    @Autowired
    private TimelineDraftEventSuggestionRepository repository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void rawInsertWithoutAuditColumns_dbDefaultFillsCreatedAndUpdatedAt() {
        String taskId = "22222222-2222-2222-2222-222222222222";
        // 구버전 AI raw INSERT 시뮬: 감사 컬럼·event_type 생략 → DB default가 채워야 한다.
        em.createNativeQuery(
                        "INSERT INTO timeline_draft_event_suggestions (task_id, user_id, start_at, title) "
                                + "VALUES (?, ?, '2026-06-17 09:00:00', ?)")
                .setParameter(1, taskId)
                .setParameter(2, 0L)
                .setParameter(3, "아침")
                .executeUpdate();
        em.flush();
        em.clear();

        List<TimelineDraftEventSuggestion> rows = repository.findByTaskId(taskId);
        assertThat(rows).hasSize(1);
        TimelineDraftEventSuggestion row = rows.get(0);
        assertThat(row.getTitle()).isEqualTo("아침");
        assertThat(row.getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 0));
        assertThat(row.getSubtitle()).isNull();
        assertThat(row.getEndAt()).isNull();
        // event_type 생략(구버전 writer) → DB default 'UNKNOWN'.
        assertThat(row.getEventType()).isEqualTo(TimelineEventType.UNKNOWN.name());
        // DB default CURRENT_TIMESTAMP가 감사 컬럼을 채웠다(AI가 안 넣어도).
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isNotNull();
    }

    @Test
    void rawInsertWithEventType_roundTripsLiteral() {
        String taskId = "44444444-4444-4444-4444-444444444444";
        // 신버전 AI raw INSERT 시뮬: event_type을 명시하면 그대로 왕복된다(서버 재추론 없음).
        em.createNativeQuery(
                        "INSERT INTO timeline_draft_event_suggestions (task_id, user_id, event_type, start_at, title) "
                                + "VALUES (?, ?, 'MEAL', '2026-06-17 12:00:00', ?)")
                .setParameter(1, taskId)
                .setParameter(2, 0L)
                .setParameter(3, "점심")
                .executeUpdate();
        em.flush();
        em.clear();

        List<TimelineDraftEventSuggestion> rows = repository.findByTaskId(taskId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(TimelineEventType.MEAL.name());
    }

    @Test
    void persistsAndReloadsFieldsViaJpa() {
        String taskId = "33333333-3333-3333-3333-333333333333";
        TimelineDraftEventSuggestion saved = repository.save(
                TimelineDraftEventSuggestion.of(taskId, 0L, TimelineEventType.WAKE_UP.name(),
                        LocalDateTime.of(2026, 6, 17, 8, 30), LocalDateTime.of(2026, 6, 17, 9, 10),
                        "제목", "부제"));

        em.flush();
        em.clear();

        TimelineDraftEventSuggestion reloaded =
                repository.findById(saved.getTimelineDraftEventSuggestionId()).orElseThrow();
        assertThat(reloaded.getTaskId()).isEqualTo(taskId);
        assertThat(reloaded.getUserId()).isEqualTo(0L);
        assertThat(reloaded.getEventType()).isEqualTo(TimelineEventType.WAKE_UP.name());
        assertThat(reloaded.getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 8, 30));
        assertThat(reloaded.getEndAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 10));
        assertThat(reloaded.getTitle()).isEqualTo("제목");
        assertThat(reloaded.getSubtitle()).isEqualTo("부제");
        // JpaRepository.save 경로는 JPA auditing이 감사 컬럼을 채운다(DB default를 덮어씀).
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }
}
