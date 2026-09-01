package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * dev 전용 AI 동기 테스트 endpoint가 <b>받는</b> body(호출자 → App Server). AI
 * {@code POST /v1/timeline/test} 요청과 같은 shape다({@code taskId}만 제외 — 서버가 발행해 붙인다).
 *
 * <p>같은 요청이 AI로 <b>나갈</b> 때의 record는 {@link AiTimelineTestInputRequest}다 — 이름이
 * {@code Ai}로 시작하면 AI와의 wire 계약, 아니면 이 endpoint의 호출자 계약이다.
 *
 * <p>{@link AiTimelineTaskInputResponse}의 {@code Window}·{@code SourceItem} record를 <b>그대로 재사용</b>한다.
 * AI 쪽도 입력 조회 응답과 테스트 요청이 같은 base 스키마를 상속하므로, 여기서 필드를 재선언하면 양쪽
 * 계약이 갈릴 수 있는 유일한 지점이 된다.
 *
 * <p>운영 입력 조회와 다른 점은 {@code window}가 필수라는 것 하나다 — 이 경로에는 시간 창을 줄 다른
 * 통로(Redis task)가 없다. {@code taskToken}은 계약에 없다: AI가 App Server를 되부르지 않는다.
 */
public record TimelineAiTestRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-06-20")
        LocalDate recordDate,
        @Schema(description = "생략하면 AI가 Asia/Seoul로 해석한다. 서버는 값을 채워 넣지 않는다",
                nullable = true, example = "Asia/Seoul")
        String recordTimeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "AI가 이벤트를 만들 시간 범위. 입력 조회 응답과 달리 필수다")
        AiTimelineTaskInputResponse.Window window,
        @Schema(description = "User Memory 문서. 서버는 해석하지 않고 그대로 전달한다", nullable = true)
        JsonNode userMemory,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "추론 대상 source item. 최소 1건이며 rawId는 canonical lowercase UUID로 중복 없이 보낸다")
        List<AiTimelineTaskInputResponse.SourceItem> sourceItems
) {
}
