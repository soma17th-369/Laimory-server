package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * AI가 콜백 전 write-then-notify로 저장하는 이벤트 제안(메타만). app↔AI 데이터 교환의 출력 방향 staging으로,
 * 입력 방향 {@link TimelineDraftSourceItem}과 대칭이다. finalize 시 timeline_events로 옮기고 삭제한다.
 *
 * <p>각 제안에 묶이는 source item은 이 테이블이 아니라 {@code timeline_draft_source_items.timeline_draft_event_suggestion_id}
 * (soft ref)가 가리킨다 — 이벤트:item = 1:N이라 FK를 "다(多)" 쪽인 item에 둔다.
 *
 * <p>이 행은 <b>AI가 raw INSERT</b>(JPA auditing 미경유)하므로 감사 컬럼(created_at/updated_at)은 DB default로 채워진다.
 * API/테스트가 JpaRepository로 save하면 JPA auditing이 값을 채워 default를 덮어쓴다(공존).
 */
@Entity
@Table(name = "timeline_draft_event_suggestions")
@Getter
public class TimelineDraftEventSuggestion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_draft_event_suggestion_id")
    private Long timelineDraftEventSuggestionId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    protected TimelineDraftEventSuggestion() {
    }

    private TimelineDraftEventSuggestion(String taskId, Long userId, LocalDateTime startAt, LocalDateTime endAt,
                                         String title, String subtitle) {
        this.taskId = taskId;
        this.userId = userId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.title = title;
        this.subtitle = subtitle;
    }

    public static TimelineDraftEventSuggestion of(String taskId, Long userId, LocalDateTime startAt,
                                                  LocalDateTime endAt, String title, String subtitle) {
        return new TimelineDraftEventSuggestion(taskId, userId, startAt, endAt, title, subtitle);
    }
}
