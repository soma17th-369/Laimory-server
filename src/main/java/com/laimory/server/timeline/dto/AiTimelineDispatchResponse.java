package com.laimory.server.timeline.dto;

/**
 * AI {@code POST /v1/timeline} 접수 성공(202 Accepted) body. 접수 확인일 뿐 final 저장 성공이 아니다 —
 * 결과는 AI가 direct-write commit 후 callback으로 알린다. dispatcher는 요청과 동일한 {@code taskId}와
 * {@code PROCESSING} status를 검증한다(불일치 = 접수 실패로 간주).
 */
public record AiTimelineDispatchResponse(
        String taskId,
        String status
) {
}
