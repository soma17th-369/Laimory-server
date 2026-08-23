package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** 그날 전체 타임라인 조회 결과(기록 ID, 기록 날짜, 기록 상태, 하루 감정, 이벤트 응답 목록). */
public record DailyTimelineResponse(
        Long dailyRecordId,
        LocalDate recordDate,
        @Schema(description = "하루 기록 상태. DRAFT(작성중) 또는 SAVED(작성완료) — null 없음.",
                example = "DRAFT")
        DailyRecordStatus status,
        @Schema(description = "저장 시 확정한 하루 감정. 저장 전 DRAFT·legacy 기록은 null.", nullable = true)
        EmotionType emotionType,
        List<TimelineEventResponse> events
) {
}
