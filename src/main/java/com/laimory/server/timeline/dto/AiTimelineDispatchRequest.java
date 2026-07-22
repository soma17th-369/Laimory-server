package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

/**
 * API→AI {@code POST /v1/timeline} 접수 body — 양 저장소가 contract fixture로 고정하는 공개 계약이다.
 * 필드명·시각 포맷을 임의로 바꾸지 않는다(명명 권위는 AI 규격).
 *
 * <p>source item은 body로 중복 전송하지 않는다 — AI가 {@code taskId}로 MySQL staging에서 직접 읽는다.
 * {@code callbackToken}은 task별 one-time 원문으로 이 body로만 한 번 전달된다(로그·MySQL·Redis 저장 금지,
 * 서버는 hash만 보관). {@code window}는 Android local window를 DailyRecord의 검증된 record_timezone으로
 * offset ISO-8601 변환한 값이다(MySQL 저장 시 AI가 다시 record timezone의 wall-clock으로 정규화).
 */
public record AiTimelineDispatchRequest(
        String taskId,
        String callbackToken,
        long dailyRecordId,
        Window window
) {

    /** AI가 이번 task에서 이벤트를 만들 시간 범위(offset 포함 ISO-8601 — 계약 포맷 고정). */
    public record Window(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt
    ) {
    }
}
