package com.laimory.server.timeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.service.UserMemoryUpdateResultService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 서버간 User Memory 갱신 결과 컨트롤러 슬라이스 테스트(MockMvc). 경로 매핑, Task-Token 헤더 전달,
 * opaque 문서의 무손실 통과, 에러 코드 매핑을 검증한다. 인프라 0.
 * (토큰 검증·지문 대조 규칙 자체는 서비스 단위 테스트가 소유한다.)
 */
@WebMvcTest(UserMemoryUpdateController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class UserMemoryUpdateControllerTest {

    private static final String RESULT = "/s/api/v1/user-memory/updates/t-1/result";
    private static final String TOKEN = "test-token";
    private static final String SUCCESS_BODY = """
            {"status":"SUCCESS","userMemory":{"schemaVersion":"1.0","currentFocus":"이사 준비",
              "customAttributes":{"pet":"고양이"}}}
            """;
    private static final String FAILED_BODY = """
            {"status":"FAILED","errorCode":1210,"error":"budget exceeded"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserMemoryUpdateResultService userMemoryUpdateResultService;

    @Test
    void result_returns200WithExplicitNullBodyAndPassesToken() throws Exception {
        mockMvc.perform(post(RESULT).header("Task-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.nullValue()));

        verify(userMemoryUpdateResultService).applyResult(eq("v1"), eq("t-1"), eq(TOKEN), any());
    }

    @Test
    void result_passesUserMemoryDocumentThroughWithoutInterpretation() throws Exception {
        mockMvc.perform(post(RESULT).header("Task-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<AiUserMemoryUpdateResultRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateResultRequest.class);
        verify(userMemoryUpdateResultService).applyResult(any(), any(), any(), request.capture());
        assertThat(request.getValue().isSuccess()).isTrue();
        // 서버가 소유하지 않는 스키마 필드까지 그대로 도착해야 한다(opaque 왕복).
        assertThat(request.getValue().userMemory().get("customAttributes").get("pet").asText())
                .isEqualTo("고양이");
    }

    @Test
    void result_acceptsFailedNotificationWith200() throws Exception {
        mockMvc.perform(post(RESULT).header("Task-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(FAILED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        ArgumentCaptor<AiUserMemoryUpdateResultRequest> request =
                ArgumentCaptor.forClass(AiUserMemoryUpdateResultRequest.class);
        verify(userMemoryUpdateResultService).applyResult(any(), any(), any(), request.capture());
        assertThat(request.getValue().isFailed()).isTrue();
        assertThat(request.getValue().errorCode()).isEqualTo(1210);
    }

    @Test
    void result_withoutTokenHeaderPassesNullToService() throws Exception {
        doThrow(new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH))
                .when(userMemoryUpdateResultService).applyResult(any(), any(), isNull(), any());

        mockMvc.perform(post(RESULT).contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-1002));
    }

    @Test
    void result_mapsTaskNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.SAVE_TASK_NOT_FOUND))
                .when(userMemoryUpdateResultService).applyResult(any(), any(), any(), any());

        mockMvc.perform(post(RESULT).header("Task-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-1001));
    }

    @Test
    void result_mapsBaseMemoryConflictTo409() throws Exception {
        doThrow(new BusinessException(ExceptionType.SAVE_TASK_STATE_CONFLICT))
                .when(userMemoryUpdateResultService).applyResult(any(), any(), any(), any());

        mockMvc.perform(post(RESULT).header("Task-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1017));
    }
}
