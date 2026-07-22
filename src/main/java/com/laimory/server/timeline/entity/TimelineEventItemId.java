package com.laimory.server.timeline.entity;

import java.io.Serializable;
import java.util.Objects;

/** {@link TimelineEventItem}의 composite PK({@code @IdClass}). JPA 요구사항대로 no-arg 생성자와 equals/hashCode를 갖는다. */
public class TimelineEventItemId implements Serializable {

    private Long timelineEventId;
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
