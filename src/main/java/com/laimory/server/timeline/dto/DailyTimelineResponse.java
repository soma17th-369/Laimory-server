package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.EmotionType;
import java.time.LocalDate;
import java.util.List;

/** 그날 전체 타임라인 조회 결과(기록 날짜, 하루 감정, 이벤트 응답 목록). */
public record DailyTimelineResponse(
        LocalDate recordDate,
        EmotionType emotionType,
        List<TimelineEventResponse> events
) {
}
