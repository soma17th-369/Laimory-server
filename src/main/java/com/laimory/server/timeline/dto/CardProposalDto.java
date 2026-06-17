package com.laimory.server.timeline.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 카드 제안(AI가 source items를 보고 반환하는 카드 초안).
 * {@code itemIds}는 이 카드에 포함하겠다고 AI가 반환한 request item id 목록이다.
 */
public record CardProposalDto(
        String title,
        String subtitle,
        LocalDateTime startAt,
        LocalDateTime endAt,
        List<Integer> itemIds
) {
}
