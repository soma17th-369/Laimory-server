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

    /**
     * 이 source item이 묶이는 이벤트 제안(=이번 task의 {@link TimelineDraftEventSuggestion} PK). soft ref(하드 FK 아님).
     * POST 시엔 null이고, AI가 콜백 전 그루핑하며 UPDATE로 채운다. finalize의 assembler가 non-null 값을
     * 이번 task의 eventRows 집합과 대조해 무결성을 검증한다(존재하지 않거나 다른 task id면 실패).
     */
    @Column(name = "timeline_draft_event_suggestion_id")
    private Long timelineDraftEventSuggestionId;

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

    /**
     * 이 source item이 묶이는 이벤트 제안 PK를 설정한다(그루핑 배정). prod에선 AI가 raw UPDATE로 채우므로 API는
     * 호출하지 않고, 이 메서드는 그 배정을 도메인으로 모델링해 테스트가 finalize 입력을 구성할 때 쓴다.
     */
    public void assignEventSuggestion(Long timelineDraftEventSuggestionId) {
        this.timelineDraftEventSuggestionId = timelineDraftEventSuggestionId;
    }
}
