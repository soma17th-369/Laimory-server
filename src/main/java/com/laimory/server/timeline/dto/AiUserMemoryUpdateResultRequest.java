package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI→API {@code POST /s/api/{v}/user-memory/updates/{taskId}/result} 결과 body — 서버간 공개 계약이다.
 *
 * <p>성공·실패가 <b>같은 endpoint</b>로 온다 — 이 호출이 결과 전달과 작업 종료 통보를 겸하므로 별도
 * callback이 없다. {@code SUCCESS}면 {@code userMemory}가 필수이고, {@code FAILED}면
 * {@code errorCode}만 로그에 남기고 자유 text {@code error}는 수신 후 폐기하며 DB는 건드리지 않는다.
 *
 * <p>{@code userMemory}는 <b>부분 병합이 아니라 문서 전체</b>다. 서버는 이 문서의 스키마를 파싱·정규화·
 * 검증하지 않는다 — 스키마를 소유하고 검증하는 쪽이 AI 서버다. 다만 저장 직전에 textual leaf만 v1
 * 개인정보 치환을 거친다(구조·필드명·숫자는 그대로).
 */
public record AiUserMemoryUpdateResultRequest(
        @Schema(description = "SUCCESS 또는 FAILED", example = "SUCCESS")
        String status,
        @Schema(description = "AI가 만든 새 User Memory 문서 전체. SUCCESS에서 필수", nullable = true)
        JsonNode userMemory,
        @Schema(description = "AI 내부 실패 코드. FAILED에서만 의미가 있고 로그로만 쓴다", nullable = true)
        Integer errorCode,
        @Schema(description = "AI 실패 설명. 서버는 저장·로그 없이 수신 후 폐기한다", nullable = true)
        String error
) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public boolean isSuccess() {
        return STATUS_SUCCESS.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }
}
