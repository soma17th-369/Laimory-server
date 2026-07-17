package com.laimory.server.timeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.TimelineEventEditService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 기록 편집 컨트롤러 슬라이스 테스트(MockMvc). 경로 매핑(PATCH/PUT)·envelope·상태 매핑(400/404/409)과
 * "userId는 컨트롤러가 결정해 서비스에 전달"(현재 DEFAULT_USER_ID=0) 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(TimelineRecordController.class)
@Import(SecurityConfig.class)
class TimelineRecordControllerTest {

    private static final String EVENT_PATH = "/a/api/v1/timeline/events/11";
    private static final String MEMO_PATH = EVENT_PATH + "/memo";

    private static final String PATCH_BODY = """
            {
              "title": "카페에서 휴식",
              "subtitle": "성수동",
              "startAt": "2026-07-08T14:00:00",
              "endAt": "2026-07-08T15:00:00"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private TimelineEventEditService timelineEventEditService;

    private TimelineEventResponse updatedEvent() {
        TimelineItemResponse item = new TimelineItemResponse(
                21L, ItemType.PHOTO, "raw-21",
                LocalDateTime.parse("2026-07-08T14:05:00"), null,
                objectMapper.valueToTree(new PhotoPayload("u.jpg", "content://x", 1.0, 2.0, null,
                        "https://cdn.example/u.jpg")));
        return new TimelineEventResponse(
                11L, LocalDateTime.parse("2026-07-08T14:00:00"), LocalDateTime.parse("2026-07-08T15:00:00"),
                "카페에서 휴식", "성수동", "기존 메모", List.of(item));
    }

    @Test
    void updateTimelineEvent_returns200WithUpdatedEvent() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(updatedEvent());

        mockMvc.perform(patch(EVENT_PATH).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelineEventId").value(11))
                .andExpect(jsonPath("$.body.title").value("카페에서 휴식"))
                .andExpect(jsonPath("$.body.subtitle").value("성수동"))
                .andExpect(jsonPath("$.body.memo").value("기존 메모"))
                .andExpect(jsonPath("$.body.items[0].timelineItemId").value(21));

        // userId는 클라 입력이 아니라 컨트롤러가 결정(DEFAULT_USER_ID=0)하고, 4개 필드를 그대로 서비스에 넘긴다.
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(0L), eq(11L),
                eq("카페에서 휴식"), eq("성수동"),
                eq(LocalDateTime.parse("2026-07-08T14:00:00")), eq(LocalDateTime.parse("2026-07-08T15:00:00")));
    }

    @Test
    void updateTimelineEvent_mapsIllegalArgumentTo400() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("title is required"));

        mockMvc.perform(patch(EVENT_PATH).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void updateTimelineEvent_mapsNotFoundTo404() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        mockMvc.perform(patch(EVENT_PATH).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("ERROR_0404"));
    }

    @Test
    void updateTimelineEvent_mapsSavedConflictTo409() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        mockMvc.perform(patch(EVENT_PATH).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1003"));
    }

    @Test
    void updateTimelineEventMemo_returns200WithUpdatedEvent() throws Exception {
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any())).thenReturn(updatedEvent());

        String body = """
                {"memo": " 원문 보존 메모 "}
                """;
        mockMvc.perform(put(MEMO_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelineEventId").value(11))
                .andExpect(jsonPath("$.body.items[0].timelineItemId").value(21));

        // memo는 컨트롤러에서 trim 없이 그대로 서비스에 전달된다(제거/보존 판정은 서비스 책임).
        verify(timelineEventEditService).updateMemo(eq("v1"), eq(0L), eq(11L), eq(" 원문 보존 메모 "));
    }

    @Test
    void updateTimelineEventMemo_emptyBodyPassesNullMemo() throws Exception {
        // body가 {}(필드 부재)면 memo=null로 서비스에 전달돼 "메모 제거"로 처리된다(계약: absent=null).
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any())).thenReturn(updatedEvent());

        mockMvc.perform(put(MEMO_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"));

        verify(timelineEventEditService).updateMemo(eq("v1"), eq(0L), eq(11L), isNull());
    }

    @Test
    void updateTimelineEventMemo_mapsIllegalArgumentTo400() throws Exception {
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("memo is too long: length=10001"));

        String body = """
                {"memo": "x"}
                """;
        mockMvc.perform(put(MEMO_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void updateTimelineEventMemo_mapsNotFoundTo404() throws Exception {
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        String body = """
                {"memo": "m"}
                """;
        mockMvc.perform(put(MEMO_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("ERROR_0404"));
    }

    @Test
    void updateTimelineEventMemo_mapsSavedConflictTo409() throws Exception {
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        String body = """
                {"memo": "m"}
                """;
        mockMvc.perform(put(MEMO_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1003"));
    }
}
