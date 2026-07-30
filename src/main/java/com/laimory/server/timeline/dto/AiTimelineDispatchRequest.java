package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

/**
 * API→AI {@code POST /v1/timeline} 접수 body — 양 저장소가 contract fixture로 고정하는 공개 계약이다.
 * 필드명·시각 포맷을 임의로 바꾸지 않는다(명명 권위는 AI 규격).
 *
 * <p>source item은 body에 싣지 않고 AI가 서버간 입력 조회 API로 가져간다. 기존 dispatch 계약의
 * {@code dailyRecordId}와 {@code window}는 그대로 유지한다.
 *
 * <p>{@code taskToken}은 입력 조회·결과 저장·콜백이 공통으로 쓰는 단일 bearer token이며,
 * 서버는 Redis에 hash만 보관한다.
 */
public record AiTimelineDispatchRequest(
        String taskId,
        String taskToken,
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
