package com.laimory.server.timeline.dto;

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
 *   <li>{@code errorCode} — AI 자체 실패 코드(양수 JSON integer — Laimory-AI {@code app/core/error_codes.py}).
 *       이벤트 0건 {@code 1211}만 {@code -1022}로 저장하고(#658), 구 계약 {@code -1008}은 그대로 받으며,
 *       그 밖의 값·null은 {@code -1008}로 수렴한다.</li>
 *   <li>{@code error} — 진단용 자유 텍스트. 사용자 원문이 섞일 수 있어 저장·클라이언트 노출은 물론
 *       서버 로그에도 남기지 않는다(수신 후 폐기 — AI wire 계약 유지를 위해 필드만 유지).</li>
 * </ul>
 */
public record DraftTaskCallbackRequest(
        TaskStatus status,
        @Schema(description = "AI 실패 코드(JSON integer). 1211(이벤트 0건)은 -1022로 저장하고, "
                + "그 밖의 값·null은 -1008로 수렴한다.",
                type = "integer", format = "int32", example = "1211", nullable = true)
        Integer errorCode,
        String error
) {
}
