package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TaskStatus;

/**
 * AI 작성 콜백 바디. {@code status}로 결과 전달(SUCCESS)인지 AI측 실패 보고(FAILED)인지 구분한다.
 *
 * <p>결과물(이벤트 제안)은 바디로 오지 않는다 — AI가 콜백 전 DB({@code timeline_draft_event_suggestions} +
 * {@code timeline_draft_source_items}의 event FK)에 write-then-notify로 저장하고, 서버가 경로의 taskId로 로드한다
 * (app↔AI 데이터 교환은 입력·출력 모두 DB 경유). 그래서 바디엔 {@code status}와, FAILED 시 사유 {@code error}만 남는다.
 * taskId·recordDate도 바디에 없다 — taskId는 URL path variable, recordDate는 task가 보관한 값을 쓴다.
 */
public record DraftTaskCallbackRequest(
        TaskStatus status,
        String error
) {
}
