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
 * <p>{@code taskToken}은 입력 조회에 사용하는 최초 opaque bearer token이다. 이후 입력·결과 성공 응답이
 * 후속 요청용 token을 새로 반환하며 서버는 Redis에 현재 token의 hash만 보관한다.
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
