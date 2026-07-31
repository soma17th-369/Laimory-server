package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.laimory.server.common.error.StrictErrorCodeDeserializer;
import com.laimory.server.timeline.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI 작성 콜백 바디. {@code status}로 완료 보고(SUCCESS)인지 AI측 실패 보고(FAILED)인지 구분한다.
 *
 * <p>결과물(Event·채택 source)은 바디로 오지 않는다 — AI가 콜백 전에 결과 저장 endpoint
 * ({@code POST /s/api/{v}/timeline/drafts/{taskId}/result})로 커밋하고, 이 콜백은 작업 상태 전이만
 * 트리거한다. taskId도 바디에 없다(URL path variable).
 *
 * <p>FAILED 보고 시:
 * <ul>
 *   <li>{@code errorCode} — 실패 분류 numeric code(허용: {@code -1008}).
 *       null/미지 값은 서버가 {@code -1008}로 폴백한다.</li>
 *   <li>{@code error} — 진단용 자유 텍스트. 저장·클라이언트 노출되지 않고 서버 로그로만 남는다.</li>
 * </ul>
 */
public record DraftTaskCallbackRequest(
        TaskStatus status,
        @Schema(description = "AI 실패 분류 code. JSON integer -1008을 사용한다.",
                type = "integer", format = "int32", example = "-1008", nullable = true)
        @JsonDeserialize(using = StrictErrorCodeDeserializer.class)
        Integer errorCode,
        String error
) {
}
