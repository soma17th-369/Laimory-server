package com.laimory.server.timeline.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.BaseEntity;
import com.laimory.server.timeline.ItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 타입은 item_type 컬럼이 권위다(payload 밖). payload는 타입 정보 없는 raw JSON({@link JsonNode})으로 보관한다.
 * start_at은 nullable(시간 미상 아이템 허용). timeline_event에 plain Long FK로 연결.
 */
@Entity
@Table(name = "timeline_items")
@Getter
public class TimelineItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_item_id")
    private Long timelineItemId;

    @Column(name = "timeline_event_id", nullable = false)
    private Long timelineEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 32)
    private ItemType itemType;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    protected TimelineItem() {
    }

    private TimelineItem(Long timelineEventId, ItemType itemType, LocalDateTime startAt,
                         LocalDateTime endAt, JsonNode payload) {
        this.timelineEventId = timelineEventId;
        this.itemType = itemType;
        this.startAt = startAt;
        this.endAt = endAt;
        this.payload = payload;
    }

    public static TimelineItem of(Long timelineEventId, ItemType itemType, LocalDateTime startAt,
                                  LocalDateTime endAt, JsonNode payload) {
        return new TimelineItem(timelineEventId, itemType, startAt, endAt, payload);
    }
}
