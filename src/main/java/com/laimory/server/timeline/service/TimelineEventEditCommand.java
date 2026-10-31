package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TimelineEventType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Event PATCH 입력을 사전 검증·정규화한 뒤 transactional writer에 넘기는 내부 command.
 * latitude·longitude는 검증을 통과한 쌍이다 — 둘 다 null이면 좌표 유지.
 */
record TimelineEventEditCommand(
        TimelineEventType eventType,
        String title,
        boolean subtitleChanged,
        String subtitle,
        LocalDateTime startAt,
        LocalDateTime endAt,
        boolean memoChanged,
        String memo,
        List<TimelineEventPhotoAddService.PhotoToAdd> photosToAdd,
        Double latitude,
        Double longitude
) {
}
