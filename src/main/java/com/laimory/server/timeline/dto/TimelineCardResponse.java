package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.entity.TimelineCard;
import java.time.LocalDateTime;
import java.util.List;

/** 타임라인 카드 응답 DTO(하위 아이템 응답 목록 포함). */
public record TimelineCardResponse(
        Long id,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String title,
        String subtitle,
        String memo,
        List<TimelineItemResponse> items
) {

    public static TimelineCardResponse from(TimelineCard card, List<TimelineItemResponse> items) {
        return new TimelineCardResponse(
                card.getId(),
                card.getStartAt(),
                card.getEndAt(),
                card.getTitle(),
                card.getSubtitle(),
                card.getMemo(),
                items
        );
    }
}
