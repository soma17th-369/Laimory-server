package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TimelineEventType;
import java.time.LocalDateTime;
import java.util.List;

/** Event PATCH 입력을 사전 검증·정규화한 뒤 transactional writer에 넘기는 내부 command. */
record TimelineEventEditCommand(
        TimelineEventType eventType,
        String title,
        String subtitle,
        LocalDateTime startAt,
        LocalDateTime endAt,
        boolean memoPresent,
        String memo,
        List<PhotoToAdd> photosToAdd
) {

    record PhotoToAdd(
            String rawId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            String filename,
            String clientPhotoUri,
            Double latitude,
            Double longitude
    ) {
    }
}
