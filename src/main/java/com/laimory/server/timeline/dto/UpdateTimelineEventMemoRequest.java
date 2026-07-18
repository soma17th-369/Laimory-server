package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 타임라인 Event 메모(PUT) 요청 — 작성·수정·제거를 하나의 계약으로 처리한다.
 *
 * <p><b>제거 규칙</b>: {@code memo}가 {@code null}이거나 공백뿐이거나 필드가 아예 없으면(body {@code {}})
 * 메모를 제거한다(null 저장). 그 외 문자열은 trim 없이 원문 그대로 저장하며,
 * {@code String.length()} 기준 최대 10,000자다(초과 시 400).
 */
public record UpdateTimelineEventMemoRequest(
        @Schema(example = "오늘은 날씨가 좋아서 오래 걸었다.",
                description = "이벤트 메모. null·공백뿐·필드 부재({})는 메모 제거. 그 외는 trim 없이 원문 저장, 최대 10,000자.")
        String memo
) {
}
