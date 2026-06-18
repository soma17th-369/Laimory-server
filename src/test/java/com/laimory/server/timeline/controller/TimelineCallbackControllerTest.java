package com.laimory.server.timeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.timeline.service.TimelineCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * 서버간 콜백 컨트롤러 슬라이스 테스트(MockMvc). secret 인터셉터(/s/**) 검증과 task 미존재 404 매핑. 인프라 0.
 */
@WebMvcTest(TimelineCallbackController.class)
@TestPropertySource(properties = "internal.callback.secret=test-secret")
class TimelineCallbackControllerTest {

    private static final String CALLBACK =
            "/s/api/v1/timeline/daily-records/draft-tasks/t-1/callback";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimelineCallbackService timelineCallbackService;

    @Test
    void callback_withValidSecret_returns200() throws Exception {
        mockMvc.perform(post(CALLBACK)
                        .header("X-Internal-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"error\":\"ai failed\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void callback_withoutSecret_returns401() throws Exception {
        mockMvc.perform(post(CALLBACK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"error\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_withWrongSecret_returns401() throws Exception {
        mockMvc.perform(post(CALLBACK)
                        .header("X-Internal-Secret", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"error\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callback_taskNotFound_returns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(timelineCallbackService).handleCallback(anyString(), anyString(), any());

        mockMvc.perform(post(CALLBACK)
                        .header("X-Internal-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\",\"sourceItems\":[],\"cards\":[]}"))
                .andExpect(status().isNotFound());
    }
}
