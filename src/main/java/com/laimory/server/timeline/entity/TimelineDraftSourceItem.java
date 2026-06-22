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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI draft 작업의 원본 source item(app이 보낸 그대로 MySQL에 보존). app↔AI 데이터 교환은 이 테이블을 경유한다.
 * 콜백 finalize 시 이 행에서 그대로 복사해 timeline_items로 옮기고 삭제한다(해피패스). 미완료 행은 cleanup이 보관기간 후 purge.
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

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "record_timezone", nullable = false)
    private String recordTimezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 32)
    private ItemType itemType;

    @Column(name = "start_at")
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    protected TimelineDraftSourceItem() {
    }

    private TimelineDraftSourceItem(String taskId, Long userId, LocalDate recordDate, String recordTimezone,
                                    ItemType itemType, LocalDateTime startAt,
                                    LocalDateTime endAt, String summary, JsonNode payload) {
        this.taskId = taskId;
        this.userId = userId;
        this.recordDate = recordDate;
        this.recordTimezone = recordTimezone;
        this.itemType = itemType;
        this.startAt = startAt;
        this.endAt = endAt;
        this.summary = summary;
        this.payload = payload;
    }

    public static TimelineDraftSourceItem of(String taskId, Long userId, LocalDate recordDate, String recordTimezone,
                                             ItemType itemType, LocalDateTime startAt,
                                             LocalDateTime endAt, String summary, JsonNode payload) {
        return new TimelineDraftSourceItem(taskId, userId, recordDate, recordTimezone, itemType,
                startAt, endAt, summary, payload);
    }
}
