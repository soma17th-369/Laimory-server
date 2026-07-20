package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** staging 조립 events ↔ 저장행 정합 검증기 단위 테스트. eventType/title/startAt 필수, itemIds 참조·유일 등. 인프라 0. */
class TimelineEventSuggestionValidatorTest {

    private final TimelineEventSuggestionValidator validator = new TimelineEventSuggestionValidator();

    private static final LocalDateTime T = LocalDateTime.of(2026, 6, 17, 9, 0);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TimelineDraftSourceItem draftRow(int id) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("task-1", 0L, ItemType.PHOTO, "r" + id,
                T, T.plusHours(1),
                MAPPER.valueToTree(new PhotoPayload("uri-" + id, "content://" + id, 1.0, 2.0, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", (long) id);
        return row;
    }

    private TimelineEventSuggestionDto event(String title, List<Long> itemIds) {
        return new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, title, "subtitle", T, T.plusHours(2), itemIds);
    }

    @Test
    void validate_passesForWellFormedPayload() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0), draftRow(1), draftRow(2));
        List<TimelineEventSuggestionDto> events = List.of(
                event("아침", List.of(0L, 2L)),
                event("점심", List.of(1L)));

        assertThatCode(() -> validator.validate(draftRows, events)).doesNotThrowAnyException();
    }

    // --- 이벤트 itemId는 저장행 PK(timeline_draft_source_item_id)에 존재 ---

    @Test
    void validate_rejectsUnknownItemIdInEvent() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(event("아침", List.of(1L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown itemId");
    }

    @Test
    void validate_rejectsNullItemIdInEvent() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(event("아침", Arrays.asList(0L, null)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown itemId");
    }

    // --- 한 itemId는 한 이벤트에만 ---

    @Test
    void validate_rejectsItemIdAssignedToMultipleEvents() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0), draftRow(1));
        List<TimelineEventSuggestionDto> events = List.of(
                event("아침", List.of(0L, 1L)),
                event("점심", List.of(1L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple events");
    }

    // --- 빈 itemIds 거부 + title 필수 ---

    @Test
    void validate_rejectsEmptyItemIds() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(event("아침", List.of()));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no itemIds");
    }

    @Test
    void validate_rejectsNullItemIds() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(event("아침", null));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no itemIds");
    }

    @Test
    void validate_rejectsBlankTitle() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(event("  ", List.of(0L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title is required");
    }

    @Test
    void validate_rejectsNullTitle() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(event(null, List.of(0L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title is required");
    }

    // --- eventType 필수 ---

    /** assembler 변환을 거친 정상 경로는 non-null이지만, DTO를 직접 만드는 경로의 안전망 검증이다. */
    @Test
    void validate_rejectsNullEventType() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(null, "아침", "sub", T, T.plusHours(1), List.of(0L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType is required");
    }

    // --- event startAt 필수 (NEW) ---

    @Test
    void validate_rejectsNullEventStartAt() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "아침", "sub", null, T.plusHours(1), List.of(0L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt is required");
    }

    // --- endAt ≥ startAt (둘 다 있을 때) ---

    @Test
    void validate_rejectsEventEndBeforeStart() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "아침", "sub", T, T.minusHours(1), List.of(0L)));

        assertThatThrownBy(() -> validator.validate(draftRows, events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endAt is before startAt");
    }

    @Test
    void validate_allowsNullEndAt() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "아침", "sub", T, null, List.of(0L)));

        assertThatCode(() -> validator.validate(draftRows, events)).doesNotThrowAnyException();
    }

    // --- 어떤 이벤트도 참조하지 않는 저장행(누락 source)은 에러 아님 ---

    @Test
    void validate_allowsUnreferencedDraftRow() {
        List<TimelineDraftSourceItem> draftRows = List.of(draftRow(0), draftRow(1), draftRow(2));
        // PK 2는 어떤 이벤트도 참조하지 않음 → 통과(미저장 대상).
        List<TimelineEventSuggestionDto> events = List.of(event("아침", List.of(0L, 1L)));

        assertThatCode(() -> validator.validate(draftRows, events)).doesNotThrowAnyException();
    }

    // --- null 인자 가드 ---

    @Test
    void validate_rejectsNullDraftRowsOrEvents() {
        assertThatThrownBy(() -> validator.validate(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draftRows is required");
        assertThatThrownBy(() -> validator.validate(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("events is required");
    }
}
