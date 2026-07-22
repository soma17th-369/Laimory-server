package com.laimory.server.timeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Event↔Item N:M junction 행. 순수 연결이라 감사 컬럼이 없고 pair가 composite PK다(중복 연결 DB 거부).
 * implicit {@code @ManyToMany} 대신 명시적 join entity를 쓴다 — 삭제 흐름의 exclusive/shared 판정과
 * orphan Item 정리가 junction을 직접 질의해야 하기 때문이다.
 *
 * <p>같은 DailyRecord 안에서만 Item을 공유한다는 규칙은 DB 제약이 아니라 writer 계약이다 —
 * final junction writer(AI·fake)는 새 Item을 현재 task의 새 Event에만 연결한다.
 */
@Entity
@Table(name = "timeline_event_items")
@IdClass(TimelineEventItemId.class)
@Getter
public class TimelineEventItem {

    @Id
    @Column(name = "timeline_event_id")
    private Long timelineEventId;

    @Id
    @Column(name = "timeline_item_id")
    private Long timelineItemId;

    protected TimelineEventItem() {
    }

    private TimelineEventItem(Long timelineEventId, Long timelineItemId) {
        this.timelineEventId = timelineEventId;
        this.timelineItemId = timelineItemId;
    }

    public static TimelineEventItem of(Long timelineEventId, Long timelineItemId) {
        return new TimelineEventItem(timelineEventId, timelineItemId);
    }
}
