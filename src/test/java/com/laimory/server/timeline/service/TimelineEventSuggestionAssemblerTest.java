package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * DB staging(event 제안 + source event_fk)을 {@link TimelineEventSuggestionDto}로 조립하는 순수 매퍼 검증.
 * 정상 그룹핑 / non-null 잘못된 event_fk→IAE / null event_fk→드롭 / 아이템 0개 이벤트→빈 itemIds
 * / raw eventType literal→enum 변환(null·blank·미지원은 IAE). 인프라 0.
 */
class TimelineEventSuggestionAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TimelineEventSuggestionAssembler assembler = new TimelineEventSuggestionAssembler();

    private TimelineDraftEventSuggestion event(long id, String title, LocalDateTime startAt) {
        return event(id, TimelineEventType.UNKNOWN.name(), title, startAt);
    }

    private TimelineDraftEventSuggestion event(long id, String eventType, String title, LocalDateTime startAt) {
        TimelineDraftEventSuggestion e = TimelineDraftEventSuggestion.of("t", 0L, eventType, startAt, null, title, null);
        ReflectionTestUtils.setField(e, "timelineDraftEventSuggestionId", id);
        return e;
    }

    private TimelineDraftSourceItem source(long pk, Long eventId) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("t", 0L, ItemType.PHOTO, "r" + pk,
                LocalDateTime.of(2026, 6, 17, 9, 0), null,
                MAPPER.valueToTree(new PhotoPayload("u" + pk, "content://" + pk, 1.0, 2.0, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", pk);
        if (eventId != null) {
            row.assignEventSuggestion(eventId);
        }
        return row;
    }

    @Test
    void assemble_groupsSourceItemsByEventFk() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, "A", t), event(2L, "B", t.plusHours(1)));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 1L), source(11L, 1L), source(12L, 2L));

        List<TimelineEventSuggestionDto> result = assembler.assemble(events, sources);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("A");
        assertThat(result.get(0).itemIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(result.get(1).title()).isEqualTo("B");
        assertThat(result.get(1).itemIds()).containsExactly(12L);
    }

    @Test
    void assemble_nullEventFk_isDropped() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, "A", t));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 1L), source(11L, null)); // 11은 미배정

        List<TimelineEventSuggestionDto> result = assembler.assemble(events, sources);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).itemIds()).containsExactly(10L); // 11은 어떤 이벤트에도 안 묶여 드롭
    }

    @Test
    void assemble_nonNullEventFkNotInEventRows_throws() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, "A", t));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 999L)); // 999는 이번 task에 없는 event

        assertThatThrownBy(() -> assembler.assemble(events, sources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void assemble_eventWithNoLinkedItems_producesEmptyItemIds() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, "A", t), event(2L, "B", t.plusHours(1)));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 1L)); // event 2엔 아무 item도 안 묶임

        List<TimelineEventSuggestionDto> result = assembler.assemble(events, sources);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).title()).isEqualTo("B");
        assertThat(result.get(1).itemIds()).isEmpty(); // validator의 'event has no itemIds'로 걸러진다
    }

    // --- raw eventType literal → enum 변환 ---

    @ParameterizedTest
    @EnumSource(TimelineEventType.class)
    void assemble_convertsEverySupportedEventTypeLiteral(TimelineEventType type) {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, type.name(), "A", t));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 1L));

        List<TimelineEventSuggestionDto> result = assembler.assemble(events, sources);

        assertThat(result.get(0).eventType()).isEqualTo(type);
    }

    /** null=구버전 스키마 없이 만든 행, blank·오타·소문자·미지원 신규 literal=writer 계약 위반 — 전부 IAE(→ 콜백 FAILED). */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "meal", "PICNIC", "MEAL "})
    void assemble_rejectsInvalidEventTypeLiteral(String raw) {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, raw, "A", t));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 1L));

        assertThatThrownBy(() -> assembler.assemble(events, sources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    /** 외부 writer가 쓴 임의 문자열이 예외 메시지로 echo되지 않는다(로그 유출 차단). */
    @Test
    void assemble_doesNotEchoRawEventTypeInErrorMessage() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftEventSuggestion> events = List.of(event(1L, "PICNIC", "A", t));
        List<TimelineDraftSourceItem> sources = List.of(source(10L, 1L));

        assertThatThrownBy(() -> assembler.assemble(events, sources))
                .hasMessageNotContaining("PICNIC");
    }
}
