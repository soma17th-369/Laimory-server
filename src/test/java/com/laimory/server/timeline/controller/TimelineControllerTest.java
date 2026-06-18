package com.laimory.server.timeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.service.TimelineCallbackService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
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
 * 컨트롤러 슬라이스 테스트(MockMvc). 상태 매핑(202/400/409/404)·secret 인터셉터(401)를 검증한다. 인프라 0.
 * secret 인터셉터/예외핸들러가 동작하도록 @WebMvcTest가 WebMvcConfigurer·HandlerInterceptor·ControllerAdvice를 로드한다.
 */
@WebMvcTest(TimelineController.class)
@TestPropertySource(properties = "internal.callback.secret=test-secret")
class TimelineControllerTest {

    private static final String TASKS = "/api/v1/timeline/daily-records/draft-tasks";
    private static final String CALLBACK =
            "/internal/api/v1/timeline/daily-records/draft-tasks/t-1/callback";

    private static final String CREATE_BODY = """
            {
              "recordDate": "2026-06-17",
              "sourceItems": [
                {"itemId": 0, "startAt": "2026-06-17T09:00:00", "endAt": null, "summary": "s",
                 "payload": {"itemType": "PHOTO", "photoUri": "u", "latitude": 1.0, "longitude": 2.0}}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimelineDraftTaskService timelineDraftTaskService;
    @MockitoBean
    private TimelineCallbackService timelineCallbackService;
    @MockitoBean
    private TimelineDraftTaskPollingService timelineDraftTaskPollingService;

    @Test
    void createDraftTask_returns202WithTaskId() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any())).thenReturn("task-123");

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-123"));
    }

    @Test
    void createDraftTask_mapsIllegalArgumentTo400() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any()))
                .thenThrow(new IllegalArgumentException("recordDate is required"));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraftTask_mapsIllegalStateTo409() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any()))
                .thenThrow(new IllegalStateException("daily record already SAVED: 1"));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void pollDraftTask_returns200WithStatus() throws Exception {
        when(timelineDraftTaskPollingService.poll("t-1")).thenReturn(DraftTaskStatusResponse.processing());

        mockMvc.perform(get(TASKS + "/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void pollDraftTask_mapsNotFoundTo404() throws Exception {
        when(timelineDraftTaskPollingService.poll("missing"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get(TASKS + "/missing"))
                .andExpect(status().isNotFound());
    }

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
                .when(timelineCallbackService).handleCallback(anyString(), any());

        mockMvc.perform(post(CALLBACK)
                        .header("X-Internal-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\",\"sourceItems\":[],\"cards\":[]}"))
                .andExpect(status().isNotFound());
    }
}
