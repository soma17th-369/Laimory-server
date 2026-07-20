package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * DB에 staging된 이벤트 제안(부모 rows) + source item(event FK 보유)을 콜백 finalize가 소비하는
 * {@link TimelineEventSuggestionDto} 리스트로 조립한다. DB 접근 없는 순수 매퍼.
 *
 * <p>이벤트:item = 1:N을 source item의 {@code timeline_draft_event_suggestion_id}(soft ref)로 표현하므로,
 * 여기서 그 soft ref의 무결성을 앱-레벨로 검증한다(하드 FK 대신): source item의 <b>non-null</b> event id는
 * 반드시 이번 task의 eventRows에 존재해야 한다 — 존재하지 않거나 다른 task의 id면 그 item이 어떤 이벤트에도
 * 안 묶여 조용히 유실되므로 {@link IllegalArgumentException}으로 실패시킨다(콜백이 잡아 FAILED 기록).
 * null event id는 "어떤 이벤트에도 안 묶기로 한 item"으로 허용(드롭 — 현행 '미참조 itemId' 동작과 동일).
 *
 * <p>조립 전에 모든 staging row의 {@code userId}가 기대 owner(task owner)와 같은지 먼저 검증한다 —
 * 불일치는 남의 데이터를 finalize할 수 있는 무결성 위반이라 {@link IllegalStateException}으로 실패시킨다
 * (콜백이 잡아 finalize 없이 FAILED 기록). row PK 외의 값은 예외 메시지에 echo하지 않는다.
 */
@Component
public class TimelineEventSuggestionAssembler {

    public List<TimelineEventSuggestionDto> assemble(long expectedUserId,
                                                     List<TimelineDraftEventSuggestion> eventRows,
                                                     List<TimelineDraftSourceItem> sourceRows) {
        // owner 검증이 association·type 변환보다 먼저다 — 남의 row는 형태가 유효해도 조립을 시작하지 않는다.
        for (TimelineDraftEventSuggestion event : eventRows) {
            if (event.getUserId() == null || event.getUserId() != expectedUserId) {
                throw new IllegalStateException("event suggestion owner mismatch: suggestionId="
                        + event.getTimelineDraftEventSuggestionId());
            }
        }
        for (TimelineDraftSourceItem source : sourceRows) {
            if (source.getUserId() == null || source.getUserId() != expectedUserId) {
                throw new IllegalStateException("source item owner mismatch: sourceItemId="
                        + source.getTimelineDraftSourceItemId());
            }
        }

        Set<Long> eventIds = new HashSet<>();
        for (TimelineDraftEventSuggestion event : eventRows) {
            eventIds.add(event.getTimelineDraftEventSuggestionId());
        }

        // event id -> 그 이벤트에 묶인 source item PK 목록.
        Map<Long, List<Long>> itemIdsByEvent = new HashMap<>();
        for (TimelineDraftSourceItem source : sourceRows) {
            Long eventId = source.getTimelineDraftEventSuggestionId();
            if (eventId == null) {
                continue;   // 미배정 item: 어떤 이벤트에도 안 들어가고 드롭.
            }
            if (!eventIds.contains(eventId)) {
                throw new IllegalArgumentException(
                        "source item references unknown event suggestion: sourceItemId="
                                + source.getTimelineDraftSourceItemId() + ", eventId=" + eventId);
            }
            itemIdsByEvent.computeIfAbsent(eventId, k -> new ArrayList<>())
                    .add(source.getTimelineDraftSourceItemId());
        }

        List<TimelineEventSuggestionDto> events = new ArrayList<>();
        for (TimelineDraftEventSuggestion event : eventRows) {
            // 아이템 0개 이벤트는 itemIds=[]로 조립돼 validator의 'event has no itemIds'로 걸러진다(→ FAILED).
            List<Long> itemIds = itemIdsByEvent.getOrDefault(event.getTimelineDraftEventSuggestionId(), List.of());
            events.add(new TimelineEventSuggestionDto(
                    convertEventType(event), event.getTitle(), event.getSubtitle(),
                    event.getStartAt(), event.getEndAt(), itemIds));
        }
        return events;
    }

    /**
     * AI가 기록한 raw literal을 {@link TimelineEventType}으로 변환하는 첫 Server 경계.
     * null/blank/미지원 literal은 {@link IllegalArgumentException}으로 실패시킨다(콜백이 잡아 FAILED 기록).
     * 예외 메시지에 raw 값은 echo하지 않는다 — 외부 writer가 쓴 임의 문자열이 로그로 새는 경로 차단.
     */
    private TimelineEventType convertEventType(TimelineDraftEventSuggestion event) {
        String raw = event.getEventType();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("event suggestion eventType is required: suggestionId="
                    + event.getTimelineDraftEventSuggestionId());
        }
        try {
            return TimelineEventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("event suggestion eventType is not supported: suggestionId="
                    + event.getTimelineDraftEventSuggestionId());
        }
    }
}
