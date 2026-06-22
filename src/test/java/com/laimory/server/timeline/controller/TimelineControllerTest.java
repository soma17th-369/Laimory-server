package com.laimory.server.timeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * 공개 컨트롤러 슬라이스 테스트(MockMvc). 상태 매핑(202/400/409/404)을 검증한다. 인프라 0.
 */
@WebMvcTest(TimelineController.class)
class TimelineControllerTest {

    private static final String TASKS = "/api/v1/timeline/drafts";

    private static final String CREATE_BODY = """
            {
              "recordAnchorAt": "2026-06-17T12:00:00",
              "recordTimeZone": "Asia/Seoul",
              "sourceItems": [
                {"itemId": 0, "itemType": "PHOTO", "startAt": "2026-06-17T09:00:00", "endAt": null, "summary": "s",
                 "payload": {"photoUri": "u", "latitude": 1.0, "longitude": 2.0}}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private TimelineDraftTaskService timelineDraftTaskService;
    @MockitoBean
    private TimelineDraftTaskPollingService timelineDraftTaskPollingService;

    @Test
    void createDraftTask_returns202WithTaskId() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any())).thenReturn("task-123");

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-123"));
    }

    @Test
    void createDraftTask_mapsIllegalArgumentTo400() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("recordDate is required"));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraftTask_mapsSavedConflictTo409() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "daily record already SAVED: 1"));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void pollDraftTask_returns200WithStatus() throws Exception {
        when(timelineDraftTaskPollingService.poll(any(), eq("t-1")))
                .thenReturn(DraftTaskStatusResponse.processing());

        mockMvc.perform(get(TASKS + "/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void pollDraftTask_mapsNotFoundTo404() throws Exception {
        when(timelineDraftTaskPollingService.poll(any(), eq("missing")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get(TASKS + "/missing"))
                .andExpect(status().isNotFound());
    }

    /**
     * SUCCESS 폴링 응답의 직렬화 계약을 컨트롤러 레벨에서 고정한다(STAGE 0 lockstep):
     * 새 이름(events/timelineEventId/timelineItemId)이 실제 JSON에 나오고, 옛 이름(cards/id)은 없어야 한다.
     */
    @Test
    void pollDraftTask_success_serializesEventContract() throws Exception {
        TimelineItemResponse item = new TimelineItemResponse(
                10L, ItemType.PHOTO,
                LocalDateTime.parse("2026-06-17T09:00:00"), null,
                objectMapper.valueToTree(new PhotoPayload("u", 1.0, 2.0)));
        TimelineEventResponse event = new TimelineEventResponse(
                1L, LocalDateTime.parse("2026-06-17T09:00:00"), null,
                "title", "subtitle", "memo", List.of(item));
        DailyTimelineResponse result = new DailyTimelineResponse(
                LocalDate.parse("2026-06-17"), null, List.of(event));
        when(timelineDraftTaskPollingService.poll(any(), eq("t-ok")))
                .thenReturn(DraftTaskStatusResponse.success(result));

        mockMvc.perform(get(TASKS + "/t-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.result.events[0].timelineEventId").value(1))
                .andExpect(jsonPath("$.result.events[0].items[0].timelineItemId").value(10))
                .andExpect(jsonPath("$.result.events[0].items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.result.cards").doesNotExist())
                .andExpect(jsonPath("$.result.events[0].id").doesNotExist())
                .andExpect(jsonPath("$.result.events[0].items[0].id").doesNotExist());
    }
}
