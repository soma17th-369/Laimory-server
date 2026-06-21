package com.laimory.server.timeline.entity;

import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 타임라인 아이템(AI가 이벤트에 포함시킨 source item이 저장된 것).
 * v1엔 item_type 컬럼이 없고 타입은 payload JSON 안 discriminator(itemType)가 단일 권위다.
 * Java 타입은 {@link #itemType()}로 취득. timeline_event에 plain Long FK로 연결.
 */
@Entity
@Table(name = "timeline_items")
@Getter
public class TimelineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_item_id")
    private Long timelineItemId;

    @Column(name = "timeline_event_id", nullable = false)
    private Long timelineEventId;

    @Column(nullable = false)
    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private TimelineItemPayload payload;

    protected TimelineItem() {
    }

    private TimelineItem(Long timelineEventId, LocalDateTime startAt, LocalDateTime endAt,
                         TimelineItemPayload payload) {
        this.timelineEventId = timelineEventId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.payload = payload;
    }

    public static TimelineItem of(Long timelineEventId, LocalDateTime startAt, LocalDateTime endAt,
                                  TimelineItemPayload payload) {
        return new TimelineItem(timelineEventId, startAt, endAt, payload);
    }

    /** payload에서 파생되는 타입(앱 switch용). */
    public ItemType itemType() {
        return payload.itemType();
    }
}
