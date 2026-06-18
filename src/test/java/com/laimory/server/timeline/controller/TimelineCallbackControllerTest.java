package com.laimory.server.timeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.timeline.service.TimelineCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * 서버간 콜백 컨트롤러 슬라이스 테스트(MockMvc). Callback-Token 헤더 전달과 401/404 매핑을 검증한다. 인프라 0.
 * (토큰 검증 자체의 정/오답 로직은 TimelineCallbackServiceTest에서 단위 검증.)
 */
@WebMvcTest(TimelineCallbackController.class)
class TimelineCallbackControllerTest {

    private static final String CALLBACK =
            "/s/api/v1/timeline/drafts/t-1/callback";
    private static final String BODY = "{\"status\":\"FAILED\",\"error\":\"x\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimelineCallbackService timelineCallbackService;

    @Test
    void callback_forwardsTokenHeader_andReturns200() throws Exception {
        mockMvc.perform(post(CALLBACK)
                        .header("Callback-Token", "tok-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verify(timelineCallbackService).handleCallback(anyString(), eq("t-1"), eq("tok-123"), any());
    }

    @Test
    void callback_serviceUnauthorized_returns401() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED))
                .when(timelineCallbackService).handleCallback(anyString(), anyString(), any(), any());

        mockMvc.perform(post(CALLBACK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_taskNotFound_returns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(timelineCallbackService).handleCallback(anyString(), anyString(), any(), any());

        mockMvc.perform(post(CALLBACK)
                        .header("Callback-Token", "tok-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\",\"sourceItems\":[],\"cards\":[]}"))
                .andExpect(status().isNotFound());
    }
}
