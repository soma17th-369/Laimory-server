package com.laimory.server.timeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.TimelineAiTestRequest;
import com.laimory.server.timeline.dto.TimelineAiTestResponse;
import com.laimory.server.timeline.service.TimelineAiTestCallException;
import com.laimory.server.timeline.service.TimelineAiTestHeaders;
import com.laimory.server.timeline.service.TimelineAiTestOutcome;
import com.laimory.server.timeline.service.TimelineAiTestService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * dev 전용 AI 동기 테스트 컨트롤러 슬라이스 테스트(MockMvc). 인프라 0.
 *
 * <p>고정하는 HTTP 계약: 성공 응답은 {@code ApiResponse} envelope <b>없이</b> typed JSON이고 서버 발행
 * {@code taskId}를 담는다, {@code X-Timeline-Timed-Out}은 AI 신호가 있을 때만 붙는다, AI 오류의 numeric
 * code는 502와 함께 {@code X-Ai-Error-Code} 헤더로 나가되 자유 text는 나가지 않는다, 그리고 오류는
 * 기존 envelope·code로 매핑된다(새 error code 없음).
 *
 * <p>입력 검증은 service 단위 테스트가, 호출자 인증은 security 계층이 소유한다 — 여기서는 전달과 매핑만 본다.
 */
@WebMvcTest(value = TimelineAiTestController.class,
        properties = "app.ai.timeline-test.enabled=true")
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TimelineAiTestControllerTest {

    private static final String PATH = "/t/api/v1/timeline/ai-results";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String TASK_ID = "0198f2a1-7c3d-7000-8b2e-1f4a9c05d6e7";
    private static final String RAW_ID = "6b5f2d3e-9c1a-4f88-9a2b-2f0d5c7e1a34";
    private static final String AI_ERROR_TEXT = "RAW_AI_ERROR_394_NEVER_EXPOSE";
    private static final String REQUEST_BODY = """
            {"recordDate":"2026-06-20","recordTimeZone":"Asia/Seoul",
             "window":{"startAt":"2026-06-20T00:00:00+09:00","endAt":"2026-06-21T00:00:00+09:00"},
             "sourceItems":[{"rawId":"%s","itemType":"STAY",
               "startAt":"2026-06-20T12:00:00+09:00","endAt":"2026-06-20T13:00:00+09:00",
               "payload":{"latitude":37.5,"longitude":127.0}}]}
            """.formatted(RAW_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimelineAiTestService timelineAiTestService;

    @Test
    void returnsTypedJsonWithoutEnvelopeAndWithoutTimedOutHeader() throws Exception {
        when(timelineAiTestService.generate(eq("v1"), any()))
                .thenReturn(outcome(false));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isOk())
                // app envelope을 쓰지 않는다 — header/body 래핑이 없어야 한다.
                .andExpect(jsonPath("$.header").doesNotExist())
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.events[0].eventType").value("MEAL"))
                .andExpect(jsonPath("$.events[0].startAt").value("2026-06-20T12:00:00+09:00"))
                .andExpect(jsonPath("$.events[0].sourceRawIds[0]").value(RAW_ID))
                .andExpect(header().doesNotExist(TimelineAiTestHeaders.TIMED_OUT))
                .andExpect(header().doesNotExist(TimelineAiTestHeaders.AI_ERROR_CODE));
    }

    @Test
    void passesRequestBodyThroughUnchanged() throws Exception {
        when(timelineAiTestService.generate(any(), any())).thenReturn(outcome(false));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<TimelineAiTestRequest> captor = ArgumentCaptor.forClass(TimelineAiTestRequest.class);
        org.mockito.Mockito.verify(timelineAiTestService).generate(eq("v1"), captor.capture());
        TimelineAiTestRequest request = captor.getValue();
        assertThat(request.recordDate().toString()).isEqualTo("2026-06-20");
        assertThat(request.recordTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(request.window().startAt()).isEqualTo(OffsetDateTime.of(2026, 6, 20, 0, 0, 0, 0, KST));
        assertThat(request.sourceItems()).hasSize(1);
        assertThat(request.sourceItems().getFirst().rawId()).isEqualTo(RAW_ID);
        assertThat(request.userMemory()).isNull();
    }

    @Test
    void addsTimedOutHeaderWhenAiReturnedItsLastConfirmedResult() throws Exception {
        when(timelineAiTestService.generate(any(), any())).thenReturn(outcome(true));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                // 실패가 아니다 — 200 + 헤더다.
                .andExpect(status().isOk())
                .andExpect(header().string(TimelineAiTestHeaders.TIMED_OUT, "true"))
                .andExpect(jsonPath("$.taskId").value(TASK_ID));
    }

    @Test
    void mapsInputViolationToValidationEnvelope() throws Exception {
        when(timelineAiTestService.generate(any(), any()))
                .thenThrow(new IllegalArgumentException("window.startAt과 window.endAt은 필수입니다."));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void exposesAiErrorCodeAsHeaderWithoutLeakingFreeText() throws Exception {
        when(timelineAiTestService.generate(any(), any()))
                .thenThrow(new TimelineAiTestCallException("AI가 오류를 반환했습니다.", 500, 1301));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isBadGateway())
                // 새 error code를 만들지 않는다 — 기존 AI_DISPATCH_FAILED로 수렴한다.
                .andExpect(jsonPath("$.header.code").value(-1009))
                .andExpect(jsonPath("$.body").doesNotExist())
                // envelope은 body=null이라 AI 코드를 담을 자리가 없다 — 헤더로 내보낸다.
                .andExpect(header().string(TimelineAiTestHeaders.AI_ERROR_CODE, "1301"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(AI_ERROR_TEXT));
    }

    @Test
    void omitsAiErrorCodeHeaderWhenAiNeverAnswered() throws Exception {
        // timeout·connect 실패는 AI 응답 자체가 없다 — 헤더 유무가 그 구분 신호다.
        when(timelineAiTestService.generate(any(), any()))
                .thenThrow(new TimelineAiTestCallException("AI 동기 테스트 호출 실패", null, null));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.code").value(-1009))
                .andExpect(header().doesNotExist(TimelineAiTestHeaders.AI_ERROR_CODE));
    }

    @Test
    void rejectsBrokenJsonBeforeReachingService() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recordDate\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineAiTestService);
    }

    private static TimelineAiTestOutcome outcome(boolean timedOut) {
        return new TimelineAiTestOutcome(
                new TimelineAiTestResponse(TASK_ID, List.of(new AiTimelineResultRequest.Event(
                        TimelineEventType.MEAL, "점심", null, null, null, null,
                        OffsetDateTime.of(2026, 6, 20, 12, 0, 0, 0, KST),
                        OffsetDateTime.of(2026, 6, 20, 13, 0, 0, 0, KST),
                        List.of(RAW_ID)))),
                timedOut);
    }
}
