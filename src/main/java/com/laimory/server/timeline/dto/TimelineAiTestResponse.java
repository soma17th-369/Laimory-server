package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * dev 전용 AI 동기 테스트 endpoint의 성공 응답 — 서버가 발행한 {@code taskId}와 AI 추론 결과다.
 * app {@code ApiResponse} envelope을 쓰지 않는다.
 *
 * <p>{@code events}는 {@link AiTimelineResultRequest.Event}를 <b>그대로 재사용</b>한다 — 운영 결과 저장
 * 계약과 같은 wire shape여야 contract 대조가 의미를 갖기 때문이며, Event를 여기서 다시 선언하지 않는다.
 * 응답 전체로 보면 결과 저장 계약에 {@code taskId} 한 키를 더한 형태이므로 계약 대조는 {@code events}
 * 서브트리를 기준으로 한다.
 *
 * <p>AI가 제한 시간 안에 마지막 확정본을 돌려준 경우 응답 헤더 {@code X-Timeline-Timed-Out: true}가
 * 함께 나간다(실패가 아니다).
 */
public record TimelineAiTestResponse(
        @Schema(description = "서버가 발행한 상관키. App Server 로그와 AI 로그·Langfuse를 잇는다",
                example = "0198f2a1-7c3d-7000-8b2e-1f4a9c05d6e7")
        String taskId,
        @Schema(description = "AI가 생성한 Event 목록(운영 결과 저장 계약과 같은 shape)")
        List<AiTimelineResultRequest.Event> events
) {
}
