package com.laimory.server.timeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.service.TimelineAiResultService;
import com.laimory.server.timeline.service.TimelineAiTaskInputService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.LocalDate;
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
 * 서버간 AI 작업 컨트롤러 슬라이스 테스트(MockMvc). Task-Token 헤더 전달, 응답 JSON 계약(시각 포맷·
 * 다음 토큰 필드), 에러 코드 매핑을 검증한다. 인프라 0.
 * (토큰 검증·저장 규칙 자체는 각 서비스 단위 테스트가 소유한다.)
 */
@WebMvcTest(TimelineAiTaskController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TimelineAiTaskControllerTest {

    private static final String INPUT = "/s/api/v1/timeline/drafts/t-1/input";
    private static final String RESULT = "/s/api/v1/timeline/drafts/t-1/result";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final String QUESTION = "그날 점심 자리에서 어떤 이야기가 가장 기억에 남았나요?";
    private static final String PLACE = "성수 카페";
    private static final String ADDRESS = "서울특별시 성동구 아차산로 17";
    // AI가 실제로 보내는 필드 구성과 key 순서를 그대로 둔다(question·place·address가 sourceRawIds 뒤).
    private static final String RESULT_BODY = """
            {"events":[{"eventType":"MEAL","title":"점심","subtitle":"회사 근처에서 점심을 해결했어요.",
              "startAt":"2026-06-17T12:00:00+09:00","endAt":"2026-06-17T13:00:00+09:00",
              "sourceRawIds":["raw-1"],"question":"그날 점심 자리에서 어떤 이야기가 가장 기억에 남았나요?",
              "place":"성수 카페","address":"서울특별시 성동구 아차산로 17"}]}
            """;
    // place·address 도입 이전 형식 — question은 있고 두 필드만 없다.
    private static final String RESULT_BODY_WITHOUT_PLACE = """
            {"events":[{"eventType":"MEAL","title":"점심","subtitle":null,
              "startAt":"2026-06-17T12:00:00+09:00","endAt":"2026-06-17T13:00:00+09:00",
              "sourceRawIds":["raw-1"],"question":"그날 점심 자리에서 어떤 이야기가 가장 기억에 남았나요?"}]}
            """;
    // question 도입 이전 형식 — 필드 자체가 없다.
    private static final String RESULT_BODY_WITHOUT_QUESTION = """
            {"events":[{"eventType":"MEAL","title":"점심","subtitle":null,
              "startAt":"2026-06-17T12:00:00+09:00","endAt":"2026-06-17T13:00:00+09:00",
              "sourceRawIds":["raw-1"]}]}
            """;
    private static final String RESULT_BODY_NULL_QUESTION = """
            {"events":[{"eventType":"MEAL","title":"점심","subtitle":null,
              "startAt":"2026-06-17T12:00:00+09:00","endAt":"2026-06-17T13:00:00+09:00",
              "sourceRawIds":["raw-1"],"question":null}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimelineAiTaskInputService timelineAiTaskInputService;
    @MockitoBean
    private TimelineAiResultService timelineAiResultService;

    private AiTimelineTaskInputResponse inputResponse() {
        return new AiTimelineTaskInputResponse("t-1", DATE, "Asia/Seoul",
                new AiTimelineTaskInputResponse.Window(
                        OffsetDateTime.of(DATE.atStartOfDay(), KST),
                        OffsetDateTime.of(DATE.plusDays(1).atStartOfDay(), KST)),
                List.of(new AiTimelineTaskInputResponse.SourceItem("raw-1", ItemType.CALENDAR,
                        OffsetDateTime.of(DATE.atTime(9, 0), KST), null,
                        JsonNodeFactory.instance.objectNode().put("title", "스탠드업"))),
                "tok-2");
    }

    @Test
    void input_forwardsTokenHeader_andReturnsCanonicalJson() throws Exception {
        when(timelineAiTaskInputService.getInput(anyString(), eq("t-1"), eq("tok-1")))
                .thenReturn(inputResponse());

        mockMvc.perform(get(INPUT).header("Task-Token", "tok-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("t-1"))
                .andExpect(jsonPath("$.recordDate").value("2026-06-17"))
                .andExpect(jsonPath("$.recordTimeZone").value("Asia/Seoul"))
                // 시각은 dispatch window와 같은 offset ISO-8601 포맷으로 고정한다(서버간 계약).
                .andExpect(jsonPath("$.window.startAt").value("2026-06-17T00:00:00+09:00"))
                .andExpect(jsonPath("$.sourceItems[0].rawId").value("raw-1"))
                .andExpect(jsonPath("$.sourceItems[0].itemType").value("CALENDAR"))
                .andExpect(jsonPath("$.sourceItems[0].startAt").value("2026-06-17T09:00:00+09:00"))
                .andExpect(jsonPath("$.sourceItems[0].payload.title").value("스탠드업"))
                .andExpect(jsonPath("$.taskToken").value("tok-2"))
                // DB 식별자·사용자 ID는 응답에 없다.
                .andExpect(jsonPath("$.dailyRecordId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void input_withoutToken_passesNullToService() throws Exception {
        // 헤더 누락은 컨트롤러가 400으로 튕기지 않고 서비스의 토큰 검증(401 -1002)으로 수렴한다.
        doThrow(new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH))
                .when(timelineAiTaskInputService).getInput(anyString(), eq("t-1"), isNull());

        mockMvc.perform(get(INPUT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-1002))
                .andExpect(header().exists("Transaction-Id"));
    }

    @Test
    void input_terminalTask_returns409() throws Exception {
        doThrow(new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT))
                .when(timelineAiTaskInputService).getInput(anyString(), anyString(), anyString());

        mockMvc.perform(get(INPUT).header("Task-Token", "tok-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1017));
    }

    @Test
    void input_taskNotFound_returns404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND))
                .when(timelineAiTaskInputService).getInput(anyString(), anyString(), anyString());

        mockMvc.perform(get(INPUT).header("Task-Token", "tok-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-1001));
    }

    @Test
    void result_forwardsTokenHeaderAndBody_andReturnsCallbackToken() throws Exception {
        when(timelineAiResultService.storeResult(anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiTimelineResultResponse("tok-3"));

        mockMvc.perform(post(RESULT)
                        .header("Task-Token", "tok-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESULT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskToken").value("tok-3"));

        ArgumentCaptor<AiTimelineResultRequest> request =
                ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultService).storeResult(anyString(), eq("t-1"), eq("tok-2"), request.capture());
        AiTimelineResultRequest.Event event = request.getValue().events().get(0);
        org.assertj.core.api.Assertions.assertThat(event.eventType()).isEqualTo(TimelineEventType.MEAL);
        org.assertj.core.api.Assertions.assertThat(event.title()).isEqualTo("점심");
        org.assertj.core.api.Assertions.assertThat(event.subtitle()).isEqualTo("회사 근처에서 점심을 해결했어요.");
        org.assertj.core.api.Assertions.assertThat(event.startAt().toInstant())
                .isEqualTo(OffsetDateTime.of(DATE.atTime(12, 0), KST).toInstant());
        org.assertj.core.api.Assertions.assertThat(event.sourceRawIds()).containsExactly("raw-1");
        org.assertj.core.api.Assertions.assertThat(event.question()).isEqualTo(QUESTION);
        org.assertj.core.api.Assertions.assertThat(event.place()).isEqualTo(PLACE);
        org.assertj.core.api.Assertions.assertThat(event.address()).isEqualTo(ADDRESS);
    }

    @Test
    void result_placeAndAddressAbsent_bindsNullWithoutError() throws Exception {
        // place·address 도입 이전 요청 형식이 그대로 통과하고 두 필드는 "값 없음"으로 수렴한다.
        when(timelineAiResultService.storeResult(anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiTimelineResultResponse("tok-3"));

        mockMvc.perform(post(RESULT)
                        .header("Task-Token", "tok-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESULT_BODY_WITHOUT_PLACE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskToken").value("tok-3"));

        ArgumentCaptor<AiTimelineResultRequest> request =
                ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultService).storeResult(anyString(), eq("t-1"), eq("tok-2"), request.capture());
        AiTimelineResultRequest.Event event = request.getValue().events().get(0);
        org.assertj.core.api.Assertions.assertThat(event.question()).isEqualTo(QUESTION);
        org.assertj.core.api.Assertions.assertThat(event.place()).isNull();
        org.assertj.core.api.Assertions.assertThat(event.address()).isNull();
    }

    @Test
    void result_questionAbsentOrNull_bindsNullWithoutError() throws Exception {
        // question 도입 이전 형식과 명시적 null 모두 계약 위반이 아니라 "질문 없음"으로 수렴한다.
        when(timelineAiResultService.storeResult(anyString(), anyString(), anyString(), any()))
                .thenReturn(new AiTimelineResultResponse("tok-3"));

        for (String body : List.of(RESULT_BODY_WITHOUT_QUESTION, RESULT_BODY_NULL_QUESTION)) {
            mockMvc.perform(post(RESULT)
                            .header("Task-Token", "tok-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskToken").value("tok-3"));
        }

        ArgumentCaptor<AiTimelineResultRequest> request =
                ArgumentCaptor.forClass(AiTimelineResultRequest.class);
        verify(timelineAiResultService, times(2))
                .storeResult(anyString(), eq("t-1"), eq("tok-2"), request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getAllValues())
                .allSatisfy(captured -> org.assertj.core.api.Assertions
                        .assertThat(captured.events().get(0).question()).isNull());
    }

    @Test
    void result_contractViolation_returns400() throws Exception {
        doThrow(new IllegalArgumentException("result requires at least one event"))
                .when(timelineAiResultService).storeResult(anyString(), anyString(), anyString(), any());

        mockMvc.perform(post(RESULT)
                        .header("Task-Token", "tok-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void result_malformedJson_isRejectedBeforeService() throws Exception {
        mockMvc.perform(post(RESULT)
                        .header("Task-Token", "tok-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(timelineAiResultService);
    }
}
