package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TaskStatus;
import java.util.List;

/**
 * AI 카드 생성 콜백 바디. {@code status}로 결과 전달(SUCCESS)인지 AI측 실패 보고(FAILED)인지 구분한다.
 *
 * <p>SUCCESS면 sourceItems + cards가 채워지고, FAILED면 error에 사유가 담긴다(재시도 소진 보고 등).
 * recordDate는 콜백 바디에 없다 — 경로의 taskId로 task를 로드해 task가 보관한 recordDate를 쓴다.
 */
public record DraftTaskCallbackRequest(
        TaskStatus status,
        String error,
        List<SourceItemDto> sourceItems,
        List<CardSuggestionDto> cards
) {
}
