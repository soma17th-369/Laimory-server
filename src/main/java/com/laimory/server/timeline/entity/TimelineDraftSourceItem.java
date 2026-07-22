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
 * AI draft 작업의 원본 source item(app이 보낸 그대로 MySQL에 보존). API→AI 입력 staging 전용이다 —
 * AI가 taskId로 읽어 final Event/Item/junction을 직접 write하고, 채택한 행만 같은 AI transaction에서
 * DELETE한다(omitted 행은 retention cleanup이 정리). {@code (task_id, raw_id)} unique가 task 안
 * rawId 중복을 DB에서 차단한다.
 *
 * <p>타입은 item_type 컬럼이 권위다(payload 밖). payload는 타입 정보 없는 raw JSON({@link JsonNode})으로 보관한다.
 */
@Entity
@Table(name = "timeline_draft_source_items")
@Getter
public class TimelineDraftSourceItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_draft_source_item_id")
    private Long timelineDraftSourceItemId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 32)
    private ItemType itemType;

    /** 클라 원본 데이터 ID(UUIDv7). payload가 아닌 envelope 필드 — 서버는 해석·정규화 없이 저장·echo만 한다. */
    @Column(name = "raw_id", nullable = false, length = 36)
    private String rawId;

    @Column(name = "start_at")
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    protected TimelineDraftSourceItem() {
    }

    private TimelineDraftSourceItem(String taskId, Long userId, ItemType itemType, String rawId,
                                    LocalDateTime startAt, LocalDateTime endAt, JsonNode payload) {
        this.taskId = taskId;
        this.userId = userId;
        this.itemType = itemType;
        this.rawId = rawId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.payload = payload;
    }

    public static TimelineDraftSourceItem of(String taskId, Long userId, ItemType itemType, String rawId,
                                             LocalDateTime startAt, LocalDateTime endAt, JsonNode payload) {
        return new TimelineDraftSourceItem(taskId, userId, itemType, rawId, startAt, endAt, payload);
    }
}
