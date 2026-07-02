package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TaskStatus;

/**
 * AI 작성 콜백 바디. {@code status}로 결과 전달(SUCCESS)인지 AI측 실패 보고(FAILED)인지 구분한다.
 *
 * <p>결과물(이벤트 제안)은 바디로 오지 않는다 — AI가 콜백 전 DB({@code timeline_draft_event_suggestions} +
 * {@code timeline_draft_source_items}의 event FK)에 write-then-notify로 저장하고, 서버가 경로의 taskId로 로드한다
 * (app↔AI 데이터 교환은 입력·출력 모두 DB 경유). taskId·recordDate도 바디에 없다 — taskId는 URL path variable,
 * recordDate는 task가 보관한 값을 쓴다.
 *
 * <p>FAILED 보고 시:
 * <ul>
 *   <li>{@code errorCode} — 실패 분류 코드(허용: {@code ERROR_1008}, 추후 확장). null/미지 값은 서버가
 *       {@code ERROR_1008}로 폴백하므로 AI는 필드를 생략해도 된다(하위호환).</li>
 *   <li>{@code error} — 진단용 자유 텍스트. 저장·클라이언트 노출되지 않고 서버 로그로만 남는다(truncate).</li>
 * </ul>
 */
public record DraftTaskCallbackRequest(
        TaskStatus status,
        String errorCode,
        String error
) {
}
