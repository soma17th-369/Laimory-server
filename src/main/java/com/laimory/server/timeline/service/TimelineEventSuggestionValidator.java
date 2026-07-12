package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * staging 관계에서 조립한 타임라인 이벤트 제안({@code events})이,
 * POST 시점에 MySQL에 저장된 draft source 행과 정합한지 검증한다.
 *
 * <p>위반 시 {@link IllegalArgumentException}을 던진다(→ 콜백에서 잡아 FAILED 기록·롤백).
 * 클라 원본은 DB의 {@code timeline_draft_source_items}에 보존돼 있으므로, 이벤트가 참조하는 itemId가 저장행의
 * PK({@code timeline_draft_source_item_id}) 집합에 실제로 존재하는지 대조한다.
 * payload/itemType 교차 검증은 하지 않는다 — itemType은 클라 discriminator를 그대로 신뢰하며
 * (EXTERNAL_PROPERTY라 sibling↔payload-subtype 불일치가 구조상 불가능), {@code ItemTypes}는 제거됐다(moot).
 */
@Component
public class TimelineEventSuggestionValidator {

    public void validate(List<TimelineDraftSourceItem> draftRows, List<TimelineEventSuggestionDto> events) {
        if (draftRows == null) {
            throw new IllegalArgumentException("draftRows is required");
        }
        if (events == null) {
            throw new IllegalArgumentException("events is required");
        }

        // 저장행의 PK(timeline_draft_source_item_id) 집합(이벤트가 참조할 수 있는 itemId의 모집단).
        Set<Long> sourceItemIds = new HashSet<>();
        for (TimelineDraftSourceItem row : draftRows) {
            sourceItemIds.add(row.getTimelineDraftSourceItemId());
        }

        Set<Long> assignedItemIds = new HashSet<>();
        for (TimelineEventSuggestionDto event : events) {
            // 규칙: title 필수.
            if (event.title() == null || event.title().isBlank()) {
                throw new IllegalArgumentException("event title is required");
            }
            // 규칙: event startAt 필수(timeline_events.start_at NOT NULL).
            if (event.startAt() == null) {
                throw new IllegalArgumentException("event startAt is required: " + event.title());
            }
            // 규칙: 둘 다 있으면 endAt ≥ startAt.
            requireValidTimeRange(event.startAt(), event.endAt(), "event " + event.title());
            // 규칙: 빈 itemIds 이벤트 거부.
            List<Long> itemIds = event.itemIds();
            if (itemIds == null || itemIds.isEmpty()) {
                throw new IllegalArgumentException("event has no itemIds: " + event.title());
            }
            for (Long itemId : itemIds) {
                // 규칙: 이벤트가 참조하는 itemId는 저장행 PK(timeline_draft_source_item_id)에 존재해야 한다.
                if (itemId == null || !sourceItemIds.contains(itemId)) {
                    throw new IllegalArgumentException("event references unknown itemId: " + itemId);
                }
                // 규칙: 한 itemId는 한 이벤트에만(전체 합집합 유일).
                if (!assignedItemIds.add(itemId)) {
                    throw new IllegalArgumentException("itemId assigned to multiple events: " + itemId);
                }
            }
        }
    }

    private void requireValidTimeRange(LocalDateTime startAt, LocalDateTime endAt, String context) {
        if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
            throw new IllegalArgumentException(context + " endAt is before startAt");
        }
    }
}
