package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 콜백 페이로드 내부 정합 검증기 단위 테스트. 규칙 1~6 각각의 통과/위반 케이스. 인프라 0. */
class CardSuggestionValidatorTest {

    private final CardSuggestionValidator validator = new CardSuggestionValidator();

    private static final LocalDateTime T = LocalDateTime.of(2026, 6, 17, 9, 0);

    private SourceItemDto source(int itemId) {
        return new SourceItemDto(itemId, ItemType.PHOTO, T, T.plusHours(1), "summary-" + itemId,
                new PhotoPayload("uri-" + itemId, 1.0, 2.0));
    }

    private CardSuggestionDto card(String title, List<Integer> itemIds) {
        return new CardSuggestionDto(title, "subtitle", T, T.plusHours(2), itemIds);
    }

    @Test
    void validate_passesForWellFormedPayload() {
        List<SourceItemDto> sources = List.of(source(0), source(1), source(2));
        List<CardSuggestionDto> cards = List.of(
                card("아침", List.of(0, 2)),
                card("점심", List.of(1)));

        assertThatCode(() -> validator.validate(sources, cards)).doesNotThrowAnyException();
    }

    // --- 규칙 1: sourceItems itemId 유일 ---

    @Test
    void validate_rejectsDuplicateSourceItemId() {
        List<SourceItemDto> sources = List.of(source(0), source(0));
        List<CardSuggestionDto> cards = List.of(card("아침", List.of(0)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void validate_rejectsNullSourceItemId() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(null, ItemType.PHOTO, T, T.plusHours(1), "s", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(card("아침", List.of(0)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null itemId");
    }

    // --- 규칙 2: 카드 itemId는 sourceItems에 존재 ---

    @Test
    void validate_rejectsUnknownItemIdInCard() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(card("아침", List.of(1)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown itemId");
    }

    @Test
    void validate_rejectsNullItemIdInCard() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(card("아침", Arrays.asList(0, null)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown itemId");
    }

    // --- 규칙 3: 한 itemId는 한 카드에만 ---

    @Test
    void validate_rejectsItemIdAssignedToMultipleCards() {
        List<SourceItemDto> sources = List.of(source(0), source(1));
        List<CardSuggestionDto> cards = List.of(
                card("아침", List.of(0, 1)),
                card("점심", List.of(1)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple cards");
    }

    // --- 규칙 4: 빈 itemIds 거부 + title 필수 ---

    @Test
    void validate_rejectsEmptyItemIds() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(card("아침", List.of()));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no itemIds");
    }

    @Test
    void validate_rejectsNullItemIds() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(card("아침", null));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no itemIds");
    }

    @Test
    void validate_rejectsBlankTitle() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(card("  ", List.of(0)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title is required");
    }

    @Test
    void validate_rejectsNullTitle() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(card(null, List.of(0)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title is required");
    }

    // --- 규칙 5(구조): endAt ≥ startAt (둘 다 있을 때) ---

    @Test
    void validate_rejectsCardEndBeforeStart() {
        List<SourceItemDto> sources = List.of(source(0));
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "sub", T, T.minusHours(1), List.of(0)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endAt is before startAt");
    }

    @Test
    void validate_rejectsSourceItemEndBeforeStart() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, T, T.minusHours(1), "s", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(card("아침", List.of(0)));

        assertThatThrownBy(() -> validator.validate(sources, cards))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endAt is before startAt");
    }

    @Test
    void validate_allowsNullEndAt() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, T, null, "s", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "sub", T, null, List.of(0)));

        assertThatCode(() -> validator.validate(sources, cards)).doesNotThrowAnyException();
    }

    // --- 규칙 6: 카드에 없는 sourceItem(누락 source)은 에러 아님 ---

    @Test
    void validate_allowsUnreferencedSourceItem() {
        List<SourceItemDto> sources = List.of(source(0), source(1), source(2));
        // itemId 2는 어떤 카드도 참조하지 않음 → 통과(미저장 대상).
        List<CardSuggestionDto> cards = List.of(card("아침", List.of(0, 1)));

        assertThatCode(() -> validator.validate(sources, cards)).doesNotThrowAnyException();
    }

    // --- null 인자 가드 ---

    @Test
    void validate_rejectsNullSourceItemsOrCards() {
        assertThatThrownBy(() -> validator.validate(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceItems is required");
        assertThatThrownBy(() -> validator.validate(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cards is required");
    }
}
