package com.laimory.server.timeline.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Event↔Item N:M junction 행. 순수 연결이라 감사 컬럼이 없고 두 FK pair가 곧 복합 PK다(중복 연결 DB 거부).
 * 키는 {@link TimelineEventItemId}를 {@code @EmbeddedId}로 품는다 — 컬럼 매핑은 키 클래스가 소유하고,
 * 호출부는 아래 편의 접근자로 두 ID에 flat하게 접근한다(id 중첩을 몰라도 됨).
 *
 * <p>implicit {@code @ManyToMany} 대신 명시적 join entity를 쓴다 — 삭제 흐름의 exclusive/shared 판정과
 * orphan Item 정리가 junction을 직접 질의해야 하기 때문이다. 같은 DailyRecord 안에서만 Item을 공유한다는
 * 규칙은 DB 제약이 아니라 writer 계약이다(AI·fake는 새 Item을 현재 task의 새 Event에 연결하고,
 * 수동 PHOTO 추가(Event PATCH·Event 생성 POST)는 같은 record의 PHOTO만 대상 Event에 재사용한다).
 */
@Entity
@Table(name = "timeline_event_items")
public class TimelineEventItem {

    @EmbeddedId
    private TimelineEventItemId id;

    protected TimelineEventItem() {
    }

    private TimelineEventItem(TimelineEventItemId id) {
        this.id = id;
    }

    public static TimelineEventItem of(Long timelineEventId, Long timelineItemId) {
        return new TimelineEventItem(new TimelineEventItemId(timelineEventId, timelineItemId));
    }

    public TimelineEventItemId getId() {
        return id;
    }

    /** 편의 접근자 — 복합 키 필드를 flat하게 노출해 호출부(조회·삭제 조립)가 id 중첩을 몰라도 되게 한다. */
    public Long getTimelineEventId() {
        return id.getTimelineEventId();
    }

    public Long getTimelineItemId() {
        return id.getTimelineItemId();
    }
}
