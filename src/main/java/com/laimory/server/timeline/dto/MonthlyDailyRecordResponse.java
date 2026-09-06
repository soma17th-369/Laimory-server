package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 월별 경량 조회의 하루 한 건 — 캘린더 화면용 read model이라 날짜·상태·감정만 담는다.
 * {@code dailyRecordId}·{@code events}는 포함하지 않는다.
 *
 * <p>field는 always-present라 required 목록도 전체여야 한다 — 일부만 선언하면 생성된 클라이언트 모델에서
 * 나머지가 nullable로 잘못 나온다.
 */
public record MonthlyDailyRecordResponse(
        @Schema(example = "2026-05-03", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate recordDate,
        @Schema(description = "하루 기록 상태. DRAFT(작성중) 또는 SAVED(작성완료) — null 없음.",
                example = "DRAFT", requiredMode = Schema.RequiredMode.REQUIRED)
        DailyRecordStatus status,
        @Schema(description = "저장 시 확정한 하루 감정. 저장 전 DRAFT·legacy 기록은 null(키 유지).",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        EmotionType emotionType
) {
}
