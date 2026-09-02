package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

/**
 * dev 전용 AI 동기 테스트에서 App Server → AI로 <b>나가는</b> body({@code POST /v1/timeline/test}) —
 * 우리가 <b>받은</b> 호출자 입력({@link TimelineAiTestRequest})에 서버 발행 {@code taskId}를 더한 것이다.
 *
 * <p>{@code Ai}로 시작하는 이름은 옆 wire DTO들과 같은 뜻이다 — 필드명·직렬화의 권위가 AI 규격에 있는
 * 계약이라는 표시다. 운영에서 AI가 <b>가져가는</b> 같은 payload가
 * {@link AiTimelineTaskInputResponse}이므로 {@code ...TestInputRequest}와 {@code ...TaskInputResponse}가
 * 방향만 다른 짝이 된다.
 *
 * <p>호출자 입력과 record를 나누는 이유는 {@code taskId}가 클라이언트 입력이 아니라 서버 생성값이기
 * 때문이다. AI는 이 값으로 조회·저장을 하지 않지만 같은 {@code taskId}로 돌린
 * 비동기 실행과 AI 로그·Langfuse에서 이어 볼 수 있다(빈 문자열은 AI가 {@code 1001}로 거절한다).
 *
 * <p>{@code taskToken}은 싣지 않는다 — AI가 App Server를 되부르지 않아 토큰이 필요 없다.
 *
 * <p>선택 필드는 {@code null}일 때 key를 생략한다(AI 계약상 "생략 시 기본값"이라 명시적 null보다 안전).
 * 중첩 {@code Window}·{@code SourceItem}은 운영 입력 조회 응답과 같은 직렬화를 그대로 쓴다.
 */
public record AiTimelineTestInputRequest(
        String taskId,
        LocalDate recordDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String recordTimeZone,
        AiTimelineTaskInputResponse.Window window,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode userMemory,
        List<AiTimelineTaskInputResponse.SourceItem> sourceItems
) {
}
