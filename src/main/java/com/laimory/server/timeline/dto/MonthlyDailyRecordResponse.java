package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.EmotionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 월별 경량 조회의 하루 한 건 — 캘린더 화면용 read model이라 날짜와 감정만 담는다.
 * {@code dailyRecordId}·{@code status}·{@code events}는 포함하지 않는다.
 */
public record MonthlyDailyRecordResponse(
        @Schema(example = "2026-05-03") LocalDate recordDate,
        @Schema(description = "저장 시 확정한 하루 감정. 저장 전 DRAFT·legacy 기록은 null(키 유지).",
                nullable = true)
        EmotionType emotionType
) {
}
