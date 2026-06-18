package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.SourceItemDto;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 콜백 페이로드(echo된 sourceItems + cards)의 내부 정합을 검증하는 순수 컴포넌트.
 *
 * <p>앞단 1차 검증으로, 위반 시 {@link IllegalArgumentException}을 던진다(→ 콜백 FAILED).
 * 클라 원본과의 대조는 sourceItems 무보관이라 불가능하므로 echo된 집합 내부 정합만 검증한다.
 * 영속 오케스트레이터({@code appendDailyTimeline})에도 동일 가드 일부가 있으나 그것은 마지막 방어선이며,
 * 여기 검증은 의도된 이중 방어다(payload sealed 서브타입 유효성은 Jackson 역직렬화가 보장).
 */
@Component
public class CardSuggestionValidator {

    public void validate(List<SourceItemDto> sourceItems, List<CardSuggestionDto> cards) {
        if (sourceItems == null) {
            throw new IllegalArgumentException("sourceItems is required");
        }
        if (cards == null) {
            throw new IllegalArgumentException("cards is required");
        }

        // 규칙 1: sourceItems의 itemId는 유일(중복·null 거부).
        Set<Integer> sourceItemIds = new HashSet<>();
        for (SourceItemDto source : sourceItems) {
            Integer itemId = source.itemId();
            if (itemId == null) {
                throw new IllegalArgumentException("sourceItem has null itemId");
            }
            if (!sourceItemIds.add(itemId)) {
                throw new IllegalArgumentException("duplicate sourceItem itemId: " + itemId);
            }
            // 규칙 5(구조): 둘 다 있으면 endAt ≥ startAt.
            requireValidTimeRange(source.startAt(), source.endAt(), "sourceItem " + itemId);
        }

        // 규칙 2~4: 카드별 itemIds 정합.
        Set<Integer> assignedItemIds = new HashSet<>();
        for (CardSuggestionDto card : cards) {
            // 규칙 4: title 필수.
            if (card.title() == null || card.title().isBlank()) {
                throw new IllegalArgumentException("card title is required");
            }
            // 규칙 4: 빈 itemIds 카드 거부.
            List<Integer> itemIds = card.itemIds();
            if (itemIds == null || itemIds.isEmpty()) {
                throw new IllegalArgumentException("card has no itemIds: " + card.title());
            }
            // 규칙 5(구조): 둘 다 있으면 endAt ≥ startAt.
            requireValidTimeRange(card.startAt(), card.endAt(), "card " + card.title());
            for (Integer itemId : itemIds) {
                // 규칙 2: 카드가 참조하는 itemId는 sourceItems에 존재해야 한다.
                if (itemId == null || !sourceItemIds.contains(itemId)) {
                    throw new IllegalArgumentException("card references unknown itemId: " + itemId);
                }
                // 규칙 3: 한 itemId는 한 카드에만(전체 합집합 유일).
                if (!assignedItemIds.add(itemId)) {
                    throw new IllegalArgumentException("itemId assigned to multiple cards: " + itemId);
                }
            }
        }
        // 규칙 6: 어떤 카드에도 안 들어간 sourceItem은 미저장(에러 아님) — 별도 처리 없음.
    }

    private void requireValidTimeRange(LocalDateTime startAt, LocalDateTime endAt, String context) {
        if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
            throw new IllegalArgumentException(context + " endAt is before startAt");
        }
    }
}
