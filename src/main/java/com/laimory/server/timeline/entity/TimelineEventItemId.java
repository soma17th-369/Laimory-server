package com.laimory.server.timeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;

/**
 * {@link TimelineEventItem}의 복합 키({@code @EmbeddedId}). junction의 두 FK가 곧 정체성이다 —
 * 컬럼 매핑을 이 클래스가 소유하고, 엔티티는 이 키를 필드 하나로 품는다.
 *
 * <p>JPA 요구사항: {@link Serializable} + no-arg 생성자 + 값 기반 equals/hashCode. equals/hashCode는
 * Hibernate가 이 객체를 영속성 컨텍스트 identity map의 키로 쓰기 때문에 필수다(같은 pair를 값으로 같다고
 * 판정해야 1차 캐시·중복 관리 판정이 동작한다).
 */
@Embeddable
@Getter
public class TimelineEventItemId implements Serializable {

    @Column(name = "timeline_event_id")
    private Long timelineEventId;

    @Column(name = "timeline_item_id")
    private Long timelineItemId;

    protected TimelineEventItemId() {
    }

    public TimelineEventItemId(Long timelineEventId, Long timelineItemId) {
        this.timelineEventId = timelineEventId;
        this.timelineItemId = timelineItemId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimelineEventItemId that)) {
            return false;
        }
        return Objects.equals(timelineEventId, that.timelineEventId)
                && Objects.equals(timelineItemId, that.timelineItemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timelineEventId, timelineItemId);
    }
}
