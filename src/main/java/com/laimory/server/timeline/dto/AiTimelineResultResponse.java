package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI result 저장 응답 — callback에 사용할 다음 task token과 이번 요청의 처리 결과.
 *
 * <p>{@code taskToken}은 재시도마다 새로 발급된다. AI는 <b>마지막으로 받은 응답의 token만</b> 사용한다.
 */
public record AiTimelineResultResponse(
        @Schema(description = "callback에 사용할 다음 task token. 재시도 응답마다 새로 발급된다")
        String taskToken,
        @Schema(description = "이번 요청이 graph를 저장했는지, 이미 저장된 결과의 재시도였는지")
        Outcome status
) {

    /** 결과 저장 처리 구분. 둘 다 성공이며 AI는 어느 쪽이든 SUCCESS callback을 보내야 종결된다. */
    public enum Outcome {
        /** 이번 요청이 graph를 저장했다. */
        STORED,
        /** 같은 결과가 이미 저장돼 있어 graph를 다시 쓰지 않고 token만 재발급했다. */
        ALREADY_PROCESSED
    }

    public static AiTimelineResultResponse stored(String taskToken) {
        return new AiTimelineResultResponse(taskToken, Outcome.STORED);
    }

    public static AiTimelineResultResponse alreadyProcessed(String taskToken) {
        return new AiTimelineResultResponse(taskToken, Outcome.ALREADY_PROCESSED);
    }
}
