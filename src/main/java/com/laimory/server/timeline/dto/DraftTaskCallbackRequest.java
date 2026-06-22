package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TaskStatus;
import java.util.List;

/**
 * AI 작성 콜백 바디. {@code status}로 결과 전달(SUCCESS)인지 AI측 실패 보고(FAILED)인지 구분한다.
 *
 * <p>SUCCESS면 {@code events}(타임라인 이벤트 제안)가 채워지고, FAILED면 error에 사유가 담긴다(재시도 소진 보고 등).
 * source item은 콜백 바디로 echo되지 않는다 — POST 시점에 MySQL {@code timeline_draft_source_items}에 저장돼 있고
 * 서버가 taskId로 로드한다(app↔AI 데이터 교환은 DB 경유).
 * recordDate는 콜백 바디에 없다 — 경로의 taskId로 task를 로드해 task가 보관한 recordDate를 쓴다.
 */
public record DraftTaskCallbackRequest(
        TaskStatus status,
        String error,
        List<TimelineEventSuggestionDto> events
) {
}
