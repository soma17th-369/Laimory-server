package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 월별 경량 조회 결과. 해당 월의 소유 하루 기록을 날짜 오름차순으로 담는다(없으면 빈 배열). */
public record MonthlyDailyRecordListResponse(
        @Schema(description = "요청 월의 하루 기록 목록(recordDate 오름차순). 기록이 없으면 빈 배열.")
        List<MonthlyDailyRecordResponse> dailyRecords
) {
}
