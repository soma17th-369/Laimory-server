package com.laimory.server.timeline.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.DailyTimelineService;
import com.laimory.server.timeline.service.TimelineDeletionService;
import com.laimory.server.timeline.service.TimelineEventEditService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 기록 조회·편집 컨트롤러 슬라이스 테스트(MockMvc). 경로 매핑(GET/PATCH/PUT/DELETE)·envelope·상태 매핑
 * (400/404/409/502)과 "userId는 인증 principal에서 서비스로 전달" 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(TimelineRecordController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TimelineRecordControllerTest {

    private static final long USER_ID = 7L;
    private static final String EVENT_PATH = "/a/api/v1/timeline/events/11";
    private static final String MEMO_PATH = EVENT_PATH + "/memo";
    private static final String DAILY_RECORDS_PATH = "/a/api/v1/timeline/daily-records";
    private static final String DAILY_RECORD_PATH = "/a/api/v1/timeline/daily-records/77";

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
    private DailyTimelineService dailyTimelineService;

    @MockitoBean
    private TimelineEventEditService timelineEventEditService;

    @MockitoBean
    private TimelineDeletionService timelineDeletionService;

    private TimelineEventResponse updatedEvent() {
        TimelineItemResponse item = new TimelineItemResponse(
                21L, ItemType.PHOTO, "raw-21",
                LocalDateTime.parse("2026-07-08T14:05:00"), null,
                objectMapper.valueToTree(new PhotoPayload("u.jpg", "content://x", 1.0, 2.0, null,
                        "https://cdn.example/u.jpg")));
        return new TimelineEventResponse(
                11L, TimelineEventType.REST,
                LocalDateTime.parse("2026-07-08T14:00:00"), LocalDateTime.parse("2026-07-08T15:00:00"),
                "카페에서 휴식", "성수동", "기존 메모", List.of(item));
    }

    private DailyTimelineResponse dailyTimeline() {
        return new DailyTimelineResponse(
                77L, LocalDate.parse("2026-07-08"), EmotionType.HAPPY, List.of(updatedEvent()));
    }

    // --- getDailyTimelines / getDailyTimeline ---

    @Test
    void getDailyTimelines_returns200WithNestedItemsAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getDailyTimelines(any(), anyLong()))
                .thenReturn(new DailyTimelinesResponse(List.of(dailyTimeline())));

        mockMvc.perform(get(DAILY_RECORDS_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelines[0].dailyRecordId").value(77))
                .andExpect(jsonPath("$.body.timelines[0].recordDate").value("2026-07-08"))
                .andExpect(jsonPath("$.body.timelines[0].emotionType").value("HAPPY"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].timelineEventId").value(11))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].rawId").value("raw-21"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].startAt")
                        .value("2026-07-08T14:05:00"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].endAt").doesNotExist())
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].payload.filename").value("u.jpg"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].payload.photoUrl")
                        .value("https://cdn.example/u.jpg"));

        verify(dailyTimelineService).getDailyTimelines("v1", USER_ID);
    }

    @Test
    void getDailyTimelines_returnsEmptyArrayWhenUserHasNoRecords() throws Exception {
        when(dailyTimelineService.getDailyTimelines(any(), anyLong()))
                .thenReturn(new DailyTimelinesResponse(List.of()));

        mockMvc.perform(get(DAILY_RECORDS_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.body.timelines").isArray())
                .andExpect(jsonPath("$.body.timelines").isEmpty());
    }

    @Test
    void getDailyTimeline_returns200WithNestedItemsAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getDailyTimeline(any(), anyLong(), anyLong()))
                .thenReturn(dailyTimeline());

        mockMvc.perform(get(DAILY_RECORD_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.dailyRecordId").value(77))
                .andExpect(jsonPath("$.body.events[0].timelineEventId").value(11))
                .andExpect(jsonPath("$.body.events[0].items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.events[0].items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.body.events[0].items[0].payload.filename").value("u.jpg"));

        verify(dailyTimelineService).getDailyTimeline("v1", USER_ID, 77L);
    }

    @Test
    void getDailyTimeline_mapsNotFoundTo404() throws Exception {
        when(dailyTimelineService.getDailyTimeline(any(), anyLong(), anyLong()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));

        mockMvc.perform(get(DAILY_RECORD_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("ERROR_0404"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void timelineReads_withoutAuthentication_return401Envelope() throws Exception {
        mockMvc.perform(get(DAILY_RECORDS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value("ERROR_2001"))
                .andExpect(jsonPath("$.body").doesNotExist());
        mockMvc.perform(get(DAILY_RECORD_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value("ERROR_2001"));

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void updateTimelineEvent_returns200WithUpdatedEvent() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenReturn(updatedEvent());

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelineEventId").value(11))
                .andExpect(jsonPath("$.body.eventType").value("REST"))
                .andExpect(jsonPath("$.body.title").value("카페에서 휴식"))
                .andExpect(jsonPath("$.body.subtitle").value("성수동"))
                .andExpect(jsonPath("$.body.memo").value("기존 메모"))
                .andExpect(jsonPath("$.body.items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.items[0].payload.description").doesNotExist())
                .andExpect(jsonPath("$.body.items[0].payload.photoUrl").value("https://cdn.example/u.jpg"));

        // 구버전 4키 요청은 그대로 호환한다. optional 키 누락은 eventType/memo 유지와 PHOTO no-op으로 전달된다.
        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(USER_ID), eq(11L), request.capture());
        assertThat(request.getValue().title()).isEqualTo("카페에서 휴식");
        assertThat(request.getValue().subtitle()).isEqualTo("성수동");
        assertThat(request.getValue().startAt()).isEqualTo(LocalDateTime.parse("2026-07-08T14:00:00"));
        assertThat(request.getValue().endAt()).isEqualTo(LocalDateTime.parse("2026-07-08T15:00:00"));
        assertThat(request.getValue().eventType()).isNull();
        assertThat(request.getValue().memoPresent()).isFalse();
        assertThat(request.getValue().memo()).isNull();
        assertThat(request.getValue().photosToAdd()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "subtitle", "startAt", "endAt"})
    void updateTimelineEvent_missingKeyRejected400(String missingKey) throws Exception {
        // 4개 키 모두 필수 계약: 키 누락은 역직렬화 단계에서 400(ERROR_0400) — 서비스에 도달하지 않는다.
        // (누락을 null로 완화하면 title·startAt만 보낸 요청이 subtitle/endAt을 조용히 지운다.)
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.remove(missingKey);

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_explicitNullClearsSubtitleAndEndAt() throws Exception {
        // 명시적 null은 누락(400)과 달리 "비움"이다 — subtitle/endAt에 null이 그대로 서비스로 전달된다.
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenReturn(updatedEvent());

        String body = """
                {
                  "title": "카페에서 휴식",
                  "subtitle": null,
                  "startAt": "2026-07-08T14:00:00",
                  "endAt": null
                }
                """;
        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"));

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(USER_ID), eq(11L), request.capture());
        assertThat(request.getValue().subtitle()).isNull();
        assertThat(request.getValue().endAt()).isNull();
    }

    @Test
    void updateTimelineEvent_withEventType_passesEnumToService() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenReturn(updatedEvent());

        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.put("eventType", "MEAL");
        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"));

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(USER_ID), eq(11L), request.capture());
        assertThat(request.getValue().eventType()).isEqualTo(TimelineEventType.MEAL);
    }

    @Test
    void updateTimelineEvent_explicitNullEventTypeRejected400() throws Exception {
        // eventType은 optional 키지만 값은 non-null 계약 — 명시적 null은 역직렬화 400(서비스 미도달).
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.putNull("eventType");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_unsupportedEventTypeLiteralRejected400() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.put("eventType", "PICNIC");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_explicitNullMemoIsPresentAndRequestsRemoval() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenReturn(updatedEvent());
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.putNull("memo");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(USER_ID), eq(11L), request.capture());
        assertThat(request.getValue().memoPresent()).isTrue();
        assertThat(request.getValue().memo()).isNull();
    }

    @Test
    void updateTimelineEvent_parsesManualPhotoAndIgnoresAiAndServerOnlyPayloadFields() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenReturn(updatedEvent());
        String body = """
                {
                  "title": "카페에서 휴식",
                  "subtitle": "성수동",
                  "startAt": "2026-07-08T14:00:00",
                  "endAt": "2026-07-08T15:00:00",
                  "memo": "사진을 정리했다.",
                  "photosToAdd": [
                    {
                      "rawId": "0190a1b2-0001-7000-8000-000000000001",
                      "startAt": "2026-07-08T14:05:00",
                      "endAt": null,
                      "payload": {
                        "filename": "0190a1b2-0002-7000-8000-000000000002.jpg",
                        "clientPhotoUri": "content://media/external/images/media/1001",
                        "latitude": 37.5665,
                        "longitude": 126.978,
                        "description": "클라이언트가 넣을 수 없는 AI 값",
                        "photoUrl": "https://attacker.example/photo.jpg"
                      }
                    }
                  ]
                }
                """;

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(USER_ID), eq(11L), request.capture());
        UpdateTimelineEventRequest parsed = request.getValue();
        assertThat(parsed.memoPresent()).isTrue();
        assertThat(parsed.memo()).isEqualTo("사진을 정리했다.");
        assertThat(parsed.photosToAdd()).hasSize(1);
        assertThat(parsed.photosToAdd().get(0).rawId())
                .isEqualTo("0190a1b2-0001-7000-8000-000000000001");
        assertThat(parsed.photosToAdd().get(0).startAt())
                .isEqualTo(LocalDateTime.parse("2026-07-08T14:05:00"));
        assertThat(parsed.photosToAdd().get(0).endAt()).isNull();
        assertThat(parsed.photosToAdd().get(0).payload().filename())
                .isEqualTo("0190a1b2-0002-7000-8000-000000000002.jpg");
        assertThat(parsed.photosToAdd().get(0).payload().clientPhotoUri())
                .isEqualTo("content://media/external/images/media/1001");
        assertThat(parsed.photosToAdd().get(0).payload().latitude()).isEqualTo(37.5665);
        assertThat(parsed.photosToAdd().get(0).payload().longitude()).isEqualTo(126.978);
        assertThat(objectMapper.valueToTree(parsed.photosToAdd().get(0).payload()).has("description")).isFalse();
        assertThat(objectMapper.valueToTree(parsed.photosToAdd().get(0).payload()).has("photoUrl")).isFalse();
    }

    @Test
    void updateTimelineEvent_explicitNullPhotosToAddRejected400() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.putNull("photosToAdd");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_mapsIllegalArgumentTo400() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("title is required"));

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_0400"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void updateTimelineEvent_mapsNotFoundTo404() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("ERROR_0404"));
    }

    @Test
    void updateTimelineEvent_mapsSavedConflictTo409() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1003"));
    }

    @Test
    void updateTimelineEvent_mapsDateGuardConflictTo409With1016() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.RECORD_DATE_IN_PROGRESS));

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1016"));
    }

    @Test
    void updateTimelineEvent_mapsPhotoCountExceededTo400With1004() throws Exception {
        when(timelineEventEditService.updateEvent(any(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, 20));

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("ERROR_1004"));
    }

    @Test
    void updateTimelineEventMemo_returns200WithUpdatedEvent() throws Exception {
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any())).thenReturn(updatedEvent());

        String body = """
                {"memo": " 원문 보존 메모 "}
                """;
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelineEventId").value(11))
                .andExpect(jsonPath("$.body.items[0].timelineItemId").value(21));

        // memo는 컨트롤러에서 trim 없이 그대로 서비스에 전달된다(제거/보존 판정은 서비스 책임).
        verify(timelineEventEditService).updateMemo(eq("v1"), eq(USER_ID), eq(11L), eq(" 원문 보존 메모 "));
    }

    @Test
    void updateTimelineEventMemo_emptyBodyPassesNullMemo() throws Exception {
        // body가 {}(필드 부재)면 memo=null로 서비스에 전달돼 "메모 제거"로 처리된다(계약: absent=null).
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any())).thenReturn(updatedEvent());

        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"));

        verify(timelineEventEditService).updateMemo(eq("v1"), eq(USER_ID), eq(11L), isNull());
    }

    @Test
    void updateTimelineEventMemo_mapsIllegalArgumentTo400() throws Exception {
        when(timelineEventEditService.updateMemo(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("memo is too long: length=10001"));

        String body = """
                {"memo": "x"}
                """;
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
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
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
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
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1003"));
    }

    // --- deleteTimelineEvent ---

    @Test
    void deleteTimelineEvent_returns200WithEmptyBody() throws Exception {
        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body").doesNotExist());

        // userId는 클라 입력이 아니라 인증 principal이다.
        verify(timelineDeletionService).deleteEvent(eq("v1"), eq(USER_ID), eq(11L));
    }

    @Test
    void deleteTimelineEvent_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND))
                .when(timelineDeletionService).deleteEvent(any(), anyLong(), any());

        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("ERROR_0404"));
    }

    @Test
    void deleteTimelineEvent_mapsSavedConflictTo409() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED))
                .when(timelineDeletionService).deleteEvent(any(), anyLong(), any());

        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1003"));
    }

    @Test
    void deleteTimelineEvent_mapsDateGuardConflictTo409With1016() throws Exception {
        doThrow(new BusinessException(ExceptionType.RECORD_DATE_IN_PROGRESS))
                .when(timelineDeletionService).deleteEvent(any(), anyLong(), any());

        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1016"));
    }

    @Test
    void deleteTimelineEvent_mapsPhotoBatchDeleteFailureTo502With1017() throws Exception {
        doThrow(new BusinessException(ExceptionType.PHOTO_BATCH_DELETE_FAILED))
                .when(timelineDeletionService).deleteEvent(any(), anyLong(), any());

        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.code").value("ERROR_1017"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    // --- deleteDailyRecord ---

    @Test
    void deleteDailyRecord_returns200WithEmptyBody() throws Exception {
        mockMvc.perform(delete(DAILY_RECORD_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body").doesNotExist());

        verify(timelineDeletionService).deleteDailyRecord(eq("v1"), eq(USER_ID), eq(77L));
    }

    @Test
    void deleteDailyRecord_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND))
                .when(timelineDeletionService).deleteDailyRecord(any(), anyLong(), any());

        mockMvc.perform(delete(DAILY_RECORD_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("ERROR_0404"));
    }

    @Test
    void deleteDailyRecord_mapsDateGuardConflictTo409With1016() throws Exception {
        doThrow(new BusinessException(ExceptionType.RECORD_DATE_IN_PROGRESS))
                .when(timelineDeletionService).deleteDailyRecord(any(), anyLong(), any());

        mockMvc.perform(delete(DAILY_RECORD_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1016"));
    }

    @Test
    void deleteDailyRecord_mapsPhotoBatchDeleteFailureTo502With1017() throws Exception {
        doThrow(new BusinessException(ExceptionType.PHOTO_BATCH_DELETE_FAILED))
                .when(timelineDeletionService).deleteDailyRecord(any(), anyLong(), any());

        mockMvc.perform(delete(DAILY_RECORD_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.code").value("ERROR_1017"));
    }
}
