package com.laimory.server.timeline.controller;

import com.laimory.server.timeline.dto.TimelineAiTestRequest;
import com.laimory.server.timeline.dto.TimelineAiTestResponse;
import com.laimory.server.timeline.service.TimelineAiTestCallException;
import com.laimory.server.timeline.service.TimelineAiTestClient;
import com.laimory.server.timeline.service.TimelineAiTestOutcome;
import com.laimory.server.timeline.service.TimelineAiTestService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev 전용 타임라인 AI 동기 테스트 구현. HTTP 문서·계약은 {@link TimelineAiTestApi}.
 *
 * <p>{@code app.ai.timeline-test.enabled=true}일 때만 빈으로 등록된다 — <b>이것이 노출 통제의 전부다.</b>
 * 미설정·prod에서는 이 빈이 없어 mapping 자체가 만들어지지 않으므로 없는 경로와 똑같은 404가 나가고
 * OpenAPI 문서에도 실리지 않는다(fail-closed).
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.timeline-test.enabled", havingValue = "true")
public class TimelineAiTestController implements TimelineAiTestApi {

    /**
     * AI가 낸 numeric errorCode를 싣는 헤더. 502 envelope은 {@code body=null}이 계약이라 서로 다른 AI
     * 실패(구조화 출력 실패·환각·시간 초과)를 호출자가 구분할 자리가 body에 없다. 자유 text
     * {@code error}는 사용자 원문이 섞일 수 있어 절대 싣지 않는다.
     */
    static final String AI_ERROR_CODE_HEADER = "X-Ai-Error-Code";

    private final TimelineAiTestService timelineAiTestService;

    @Override
    public ResponseEntity<TimelineAiTestResponse> generate(String applicationVersion,
                                                           TimelineAiTestRequest request,
                                                           HttpServletResponse response) {
        TimelineAiTestOutcome outcome;
        try {
            outcome = timelineAiTestService.generate(applicationVersion, request);
        } catch (TimelineAiTestCallException e) {
            // 에러 envelope은 body=null이 계약이라 AI가 낸 코드를 담을 자리가 없다 — 헤더로만 내보낸다.
            // 예외 핸들러가 status·envelope을 쓰기 전에 세워두면 그대로 남는다.
            if (e.getAiErrorCode() != null) {
                response.setHeader(AI_ERROR_CODE_HEADER, String.valueOf(e.getAiErrorCode()));
            }
            throw e;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (outcome.timedOut()) {
            // 실패가 아니라 "제한 시간 내 마지막 확정본"이라는 AI 신호를 그대로 전달한다.
            builder = builder.header(TimelineAiTestClient.TIMED_OUT_HEADER, "true");
        }
        return builder.body(new TimelineAiTestResponse(outcome.taskId(), outcome.events()));
    }
}
