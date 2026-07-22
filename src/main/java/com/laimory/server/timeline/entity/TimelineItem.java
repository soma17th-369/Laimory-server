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
 * 타임라인 아이템(source item이 final로 저장된 것). Event와는 {@link TimelineEventItem}(junction)으로만
 * 연결되는 독립 행이다 — 하루 범위는 junction→Event→DailyRecord로 해석하고 직접 FK를 두지 않는다.
 * 타입은 item_type 컬럼이 권위다(payload 밖). payload는 타입 정보 없는 raw JSON({@link JsonNode})으로 보관한다.
 * start_at은 nullable(시간 미상 아이템 허용).
 *
 * <p>이 테이블은 API JPA와 AI raw INSERT 두 writer가 쓴다 — 감사 timestamp는 DB default가 겸하고,
 * rawId 중복은 DB가 거부하지 않는다(같은 record 안 중복 방지는 application-level 방어).
 */
@Entity
@Table(name = "timeline_items")
@Getter
public class TimelineItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_item_id")
    private Long timelineItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 32)
    private ItemType itemType;

    /** 클라 원본 데이터 ID(UUIDv7). payload가 아닌 envelope 필드 — draft 행에서 그대로 복사되며 응답에 echo된다. */
    @Column(name = "raw_id", nullable = false, length = 36)
    private String rawId;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    protected TimelineItem() {
    }

    private TimelineItem(ItemType itemType, String rawId, LocalDateTime startAt,
                         LocalDateTime endAt, JsonNode payload) {
        this.itemType = itemType;
        this.rawId = rawId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.payload = payload;
    }

    public static TimelineItem of(ItemType itemType, String rawId, LocalDateTime startAt,
                                  LocalDateTime endAt, JsonNode payload) {
        return new TimelineItem(itemType, rawId, startAt, endAt, payload);
    }
}
