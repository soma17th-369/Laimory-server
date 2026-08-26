package com.laimory.server.timeline.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static com.laimory.server.testsupport.TestSubjects.id;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.terms.service.TermsEnforcementService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordListResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.DailyRecordEmotionUpdateService;
import com.laimory.server.timeline.service.DailyTimelineService;
import com.laimory.server.timeline.service.TimelineDeletionService;
import com.laimory.server.timeline.service.TimelineEventCreateService;
import com.laimory.server.timeline.service.TimelineEventEditService;
import com.laimory.server.timeline.service.TimelineSaveService;
import com.laimory.server.user.service.SubjectMappingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * (400/404/409)과 "userId는 인증 principal에서 서비스로 전달" 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(TimelineRecordController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TimelineRecordControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = id(USER_ID);
    private static final LocalDate RECORD_DATE = LocalDate.parse("2026-07-08");
    private static final String EVENT_PATH = "/a/api/v1/timeline/events/11";
    private static final String MEMO_PATH = EVENT_PATH + "/memo";
    private static final String EVENT_ITEM_PATH = EVENT_PATH + "/items/21";
    private static final String DAILY_RECORDS_PATH = "/a/api/v1/timeline/daily-records";
    private static final String DAILY_RECORD_ID_PATH = "/a/api/v1/timeline/daily-records/by-id/77";
    private static final String DAILY_RECORD_DATE_PATH = DAILY_RECORDS_PATH + "/" + RECORD_DATE;
    private static final String INVALID_DAILY_RECORD_DATE_PATH = DAILY_RECORDS_PATH + "/not-a-date";
    private static final String SAVE_DAILY_RECORD_DATE_PATH = DAILY_RECORD_DATE_PATH + "/save";
    private static final String EMOTION_DATE_PATH = DAILY_RECORD_DATE_PATH + "/emotion";
    private static final String CREATE_EVENT_DATE_PATH = DAILY_RECORD_DATE_PATH + "/events";
    private static final String MONTHLY_RECORDS_PATH = "/a/api/v1/timeline/monthly-records";

    private static final String SAVE_BODY = """
            {"emotionType": "HAPPY"}
            """;

    private static final String CREATE_EVENT_BODY = """
            {
              "eventType": "REST",
              "title": "카페에서 휴식",
              "subtitle": "성수동",
              "startAt": "2026-07-08T14:00:00",
              "endAt": "2026-07-08T15:00:00",
              "memo": "책을 읽었다."
            }
            """;

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

    @MockitoBean
    private TimelineSaveService timelineSaveService;
    @MockitoBean
    private DailyRecordEmotionUpdateService dailyRecordEmotionUpdateService;
    @MockitoBean
    private TimelineEventCreateService timelineEventCreateService;
    @MockitoBean
    private SubjectMappingService subjectMappingService;
    // 약관 gate interceptor(#303)가 슬라이스에도 적용된다 — 기본 no-op mock이면 gate 통과.
    @MockitoBean
    private TermsEnforcementService termsEnforcementService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    private TimelineEventResponse updatedEvent() {
        TimelineItemResponse item = new TimelineItemResponse(
                21L, ItemType.PHOTO, "raw-21",
                LocalDateTime.parse("2026-07-08T14:05:00"), null,
                objectMapper.valueToTree(new PhotoPayload("u.jpg", "content://x", 1.0, 2.0, null,
                        null, null,
                        "https://cdn.example/u.jpg")));
        return new TimelineEventResponse(
                11L, TimelineEventType.REST,
                LocalDateTime.parse("2026-07-08T14:00:00"), LocalDateTime.parse("2026-07-08T15:00:00"),
                "카페에서 휴식", "성수동", "누구와 함께 있었나요?",
                "성수 카페", "서울특별시 성동구 아차산로 17", "기존 메모", List.of(item));
    }

    private DailyTimelineResponse dailyTimeline() {
        return new DailyTimelineResponse(
                77L, LocalDate.parse("2026-07-08"), DailyRecordStatus.SAVED, EmotionType.HAPPY,
                List.of(updatedEvent()));
    }

    // --- getDailyTimelines / getDailyTimeline / getTimelineEvent ---

    @Test
    void getDailyTimelines_returns200WithNestedItemsAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getDailyTimelines(any(), any()))
                .thenReturn(new DailyTimelinesResponse(List.of(dailyTimeline())));

        mockMvc.perform(get(DAILY_RECORDS_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelines[0].dailyRecordId").value(77))
                .andExpect(jsonPath("$.body.timelines[0].recordDate").value("2026-07-08"))
                .andExpect(jsonPath("$.body.timelines[0].status").value("SAVED"))
                .andExpect(jsonPath("$.body.timelines[0].emotionType").value("HAPPY"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].timelineEventId").value(11))
                .andExpect(jsonPath("$.body.timelines[0].events[0].question").value("누구와 함께 있었나요?"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].place").value("성수 카페"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].address")
                        .value("서울특별시 성동구 아차산로 17"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].rawId").value("raw-21"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].startAt")
                        .value("2026-07-08T14:05:00"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].endAt").doesNotExist())
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].payload.filename").value("u.jpg"))
                .andExpect(jsonPath("$.body.timelines[0].events[0].items[0].payload.photoUrl")
                        .value("https://cdn.example/u.jpg"));

        verify(dailyTimelineService).getDailyTimelines("v1", SUBJECT_ID);
    }

    @Test
    void getDailyTimelines_returnsEmptyArrayWhenUserHasNoRecords() throws Exception {
        when(dailyTimelineService.getDailyTimelines(any(), any()))
                .thenReturn(new DailyTimelinesResponse(List.of()));

        mockMvc.perform(get(DAILY_RECORDS_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.timelines").isArray())
                .andExpect(jsonPath("$.body.timelines").isEmpty());
    }

    @Test
    void getDailyTimelineById_returns200WithNestedItemsAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getDailyTimeline(any(), any(), anyLong()))
                .thenReturn(dailyTimeline());

        mockMvc.perform(get(DAILY_RECORD_ID_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.dailyRecordId").value(77))
                .andExpect(jsonPath("$.body.events[0].timelineEventId").value(11))
                .andExpect(jsonPath("$.body.events[0].items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.events[0].items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.body.events[0].items[0].payload.filename").value("u.jpg"));

        verify(dailyTimelineService).getDailyTimeline("v1", SUBJECT_ID, 77L);
    }

    @Test
    void getDailyTimelineById_mapsNotFoundTo404() throws Exception {
        when(dailyTimelineService.getDailyTimeline(any(), any(), anyLong()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));

        mockMvc.perform(get(DAILY_RECORD_ID_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void getDailyTimelineByDate_returns200WithNestedItemsAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getDailyTimeline(any(), any(), any(LocalDate.class)))
                .thenReturn(dailyTimeline());

        mockMvc.perform(get(DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.dailyRecordId").value(77))
                .andExpect(jsonPath("$.body.recordDate").value("2026-07-08"))
                .andExpect(jsonPath("$.body.status").value("SAVED"))
                .andExpect(jsonPath("$.body.events[0].timelineEventId").value(11))
                .andExpect(jsonPath("$.body.events[0].items[0].timelineItemId").value(21));

        verify(dailyTimelineService).getDailyTimeline("v1", SUBJECT_ID, RECORD_DATE);
    }

    @Test
    void getDailyTimelineByDate_mapsNotFoundTo404() throws Exception {
        when(dailyTimelineService.getDailyTimeline(any(), any(), any(LocalDate.class)))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));

        mockMvc.perform(get(DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void getDailyTimelineByDate_withInvalidDateReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(get(INVALID_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void recordDatePaths_outsideMysqlDateRange_return400WithoutCallingService() throws Exception {
        // ISO parse는 통과하지만 MySQL DATE가 담지 못하는 값 — 다섯 path 모두 service 호출 전에 끊는다.
        // "파싱 실패"(위 테스트)와 "파싱은 되지만 범위 밖"은 다른 경계라 함께 고정한다.
        for (String date : new String[] {"0999-12-31", "+10000-01-01"}) {
            String base = DAILY_RECORDS_PATH + "/" + date;

            mockMvc.perform(get(base).with(authenticatedUser(USER_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
            mockMvc.perform(delete(base).with(authenticatedUser(USER_ID)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
            mockMvc.perform(post(base + "/save").with(authenticatedUser(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
            mockMvc.perform(put(base + "/emotion").with(authenticatedUser(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
            mockMvc.perform(post(base + "/events").with(authenticatedUser(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.code").value(-400));
        }

        verifyNoInteractions(dailyTimelineService, timelineDeletionService, timelineSaveService,
                dailyRecordEmotionUpdateService, timelineEventCreateService);
    }

    // --- getMonthlyDailyRecords (캘린더 월별 경량 조회) ---

    @Test
    void getMonthlyDailyRecords_returns200WithDateAndNullableEmotionAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getMonthlyDailyRecords(any(), any(), eq(2026), eq(5)))
                .thenReturn(new MonthlyDailyRecordListResponse(List.of(
                        new MonthlyDailyRecordResponse(LocalDate.parse("2026-05-03"), null),
                        new MonthlyDailyRecordResponse(LocalDate.parse("2026-05-19"), EmotionType.HAPPY))));

        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "2026").queryParam("month", "5")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.dailyRecords[0].recordDate").value("2026-05-03"))
                .andExpect(jsonPath("$.body.dailyRecords[1].recordDate").value("2026-05-19"))
                .andExpect(jsonPath("$.body.dailyRecords[1].emotionType").value("HAPPY"))
                // 캘린더 응답은 경량 read model이다 — dailyRecordId·status·events 키가 없어야 한다.
                .andExpect(jsonPath("$.body.dailyRecords[0].dailyRecordId").doesNotExist())
                .andExpect(jsonPath("$.body.dailyRecords[0].status").doesNotExist())
                .andExpect(jsonPath("$.body.dailyRecords[0].events").doesNotExist())
                // null 감정은 key 생략(NON_NULL)이 아니라 명시적 JSON null이다.
                .andExpect(result -> {
                    JsonNode first = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                            .at("/body/dailyRecords/0");
                    assertThat(first.has("emotionType")).isTrue();
                    assertThat(first.get("emotionType").isNull()).isTrue();
                });

        verify(dailyTimelineService).getMonthlyDailyRecords("v1", SUBJECT_ID, 2026, 5);
    }

    @Test
    void getMonthlyDailyRecords_emptyMonthReturns200WithEmptyArray() throws Exception {
        when(dailyTimelineService.getMonthlyDailyRecords(any(), any(), eq(2026), eq(6)))
                .thenReturn(new MonthlyDailyRecordListResponse(List.of()));

        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "2026").queryParam("month", "6")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.dailyRecords").isArray())
                .andExpect(jsonPath("$.body.dailyRecords").isEmpty());
    }

    @Test
    void getMonthlyDailyRecords_missingParamReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "2026")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("month", "5")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void getMonthlyDailyRecords_nonIntegerParamReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "20x6").queryParam("month", "5")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "2026").queryParam("month", "5.5")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void getMonthlyDailyRecords_mapsIllegalArgumentTo400() throws Exception {
        when(dailyTimelineService.getMonthlyDailyRecords(any(), any(), eq(2026), eq(13)))
                .thenThrow(new IllegalArgumentException("month must be between 1 and 12: 13"));

        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "2026").queryParam("month", "13")
                        .with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void getMonthlyDailyRecords_withoutAuthenticationReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(get(MONTHLY_RECORDS_PATH).queryParam("year", "2026").queryParam("month", "5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void getTimelineEvent_returns200WithNestedItemsAndPassesPrincipal() throws Exception {
        when(dailyTimelineService.getTimelineEvent(any(), any(), anyLong()))
                .thenReturn(updatedEvent());

        mockMvc.perform(get(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelineEventId").value(11))
                .andExpect(jsonPath("$.body.eventType").value("REST"))
                .andExpect(jsonPath("$.body.question").value("누구와 함께 있었나요?"))
                .andExpect(jsonPath("$.body.place").value("성수 카페"))
                .andExpect(jsonPath("$.body.address").value("서울특별시 성동구 아차산로 17"))
                .andExpect(jsonPath("$.body.items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.items[0].payload.filename").value("u.jpg"));

        verify(dailyTimelineService).getTimelineEvent("v1", SUBJECT_ID, 11L);
    }

    @Test
    void getTimelineEvent_mapsNotFoundTo404() throws Exception {
        when(dailyTimelineService.getTimelineEvent(any(), any(), anyLong()))
                .thenThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        mockMvc.perform(get(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void timelineReads_withoutAuthentication_return401Envelope() throws Exception {
        mockMvc.perform(get(DAILY_RECORDS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());
        mockMvc.perform(get(DAILY_RECORD_ID_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(get(DAILY_RECORD_DATE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(get(EVENT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void updateTimelineEvent_returns200WithExplicitNullBody() throws Exception {
        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        // 구버전 4키 요청은 그대로 호환한다. optional 키 누락은 eventType/memo 유지와 PHOTO no-op으로 전달된다.
        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(SUBJECT_ID), eq(11L), request.capture());
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
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_explicitNullClearsSubtitleAndEndAt() throws Exception {
        // 명시적 null은 누락(400)과 달리 "비움"이다 — subtitle/endAt에 null이 그대로 서비스로 전달된다.
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
                .andExpect(jsonPath("$.header.code").value(0));

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(SUBJECT_ID), eq(11L), request.capture());
        assertThat(request.getValue().subtitle()).isNull();
        assertThat(request.getValue().endAt()).isNull();
    }

    @Test
    void updateTimelineEvent_withEventType_passesEnumToService() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.put("eventType", "MEAL");
        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(SUBJECT_ID), eq(11L), request.capture());
        assertThat(request.getValue().eventType()).isEqualTo(TimelineEventType.MEAL);
    }

    @Test
    void updateTimelineEvent_explicitNullEventTypeRejected400() throws Exception {
        // eventType은 optional 키지만 값은 non-null 계약 — 명시적 null은 역직렬화 400(서비스 미도달).
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.putNull("eventType");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_unsupportedEventTypeLiteralRejected400() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.put("eventType", "PICNIC");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_explicitNullMemoIsPresentAndRequestsRemoval() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(PATCH_BODY);
        body.putNull("memo");

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateTimelineEventRequest> request = ArgumentCaptor.forClass(UpdateTimelineEventRequest.class);
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(SUBJECT_ID), eq(11L), request.capture());
        assertThat(request.getValue().memoPresent()).isTrue();
        assertThat(request.getValue().memo()).isNull();
    }

    @Test
    void updateTimelineEvent_parsesManualPhotoAndIgnoresAiAndServerOnlyPayloadFields() throws Exception {
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
        verify(timelineEventEditService).updateEvent(eq("v1"), eq(SUBJECT_ID), eq(11L), request.capture());
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
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventEditService);
    }

    @Test
    void updateTimelineEvent_mapsIllegalArgumentTo400() throws Exception {
        doThrow(new IllegalArgumentException("title is required"))
                .when(timelineEventEditService).updateEvent(any(), any(), any(), any());

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void updateTimelineEvent_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND))
                .when(timelineEventEditService).updateEvent(any(), any(), any(), any());

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void updateTimelineEvent_mapsPhotoCountExceededTo400With1004() throws Exception {
        doThrow(new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, 20))
                .when(timelineEventEditService).updateEvent(any(), any(), any(), any());

        mockMvc.perform(patch(EVENT_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-1004));
    }

    @Test
    void updateTimelineEventMemo_returns200WithExplicitNullBody() throws Exception {
        String body = """
                {"memo": " 원문 보존 메모 "}
                """;
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        // memo는 컨트롤러에서 trim 없이 그대로 서비스에 전달된다(제거/보존 판정은 서비스 책임).
        verify(timelineEventEditService).updateMemo(eq("v1"), eq(SUBJECT_ID), eq(11L), eq(" 원문 보존 메모 "));
    }

    @Test
    void updateTimelineEventMemo_emptyBodyPassesNullMemo() throws Exception {
        // body가 {}(필드 부재)면 memo=null로 서비스에 전달돼 "메모 제거"로 처리된다(계약: absent=null).
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(this::assertBodyIsExplicitNull);

        verify(timelineEventEditService).updateMemo(eq("v1"), eq(SUBJECT_ID), eq(11L), isNull());
    }

    @Test
    void updateTimelineEventMemo_mapsIllegalArgumentTo400() throws Exception {
        doThrow(new IllegalArgumentException("memo is too long: length=501"))
                .when(timelineEventEditService).updateMemo(any(), any(), any(), any());

        String body = """
                {"memo": "x"}
                """;
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void updateTimelineEventMemo_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND))
                .when(timelineEventEditService).updateMemo(any(), any(), any(), any());

        String body = """
                {"memo": "m"}
                """;
        mockMvc.perform(put(MEMO_PATH).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    // --- deleteTimelineEvent ---

    @Test
    void deleteTimelineEvent_returns200WithEmptyBody() throws Exception {
        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        // userId는 클라 입력이 아니라 인증 principal이다.
        verify(timelineDeletionService).deleteEvent(eq("v1"), eq(SUBJECT_ID), eq(11L));
    }

    @Test
    void deleteTimelineEvent_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND))
                .when(timelineDeletionService).deleteEvent(any(), any(), any());

        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void deleteTimelineEvent_mapsUnexpectedTransactionFailureTo500() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(timelineDeletionService).deleteEvent(any(), any(), any());

        mockMvc.perform(delete(EVENT_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.code").value(-500));
    }

    // --- detachTimelineEventItem ---

    @Test
    void detachTimelineEventItem_returns200WithEmptyBody() throws Exception {
        mockMvc.perform(delete(EVENT_ITEM_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        // userId는 클라 입력이 아니라 인증 principal이다.
        verify(timelineDeletionService).detachEventItem(eq("v1"), eq(SUBJECT_ID), eq(11L), eq(21L));
    }

    @Test
    void detachTimelineEventItem_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_FOUND))
                .when(timelineDeletionService).detachEventItem(any(), any(), any(), any());

        mockMvc.perform(delete(EVENT_ITEM_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void detachTimelineEventItem_mapsNonPhotoRejectionTo400() throws Exception {
        doThrow(new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_PHOTO))
                .when(timelineDeletionService).detachEventItem(any(), any(), any(), any());

        mockMvc.perform(delete(EVENT_ITEM_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-1018));
    }

    // --- deleteDailyRecord / deleteDailyRecordByDate ---

    @Test
    void deleteDailyRecordById_returns200WithEmptyBody() throws Exception {
        mockMvc.perform(delete(DAILY_RECORD_ID_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        verify(timelineDeletionService).deleteDailyRecord(eq("v1"), eq(SUBJECT_ID), eq(77L));
    }

    @Test
    void deleteDailyRecordById_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND))
                .when(timelineDeletionService).deleteDailyRecord(any(), any(), any());

        mockMvc.perform(delete(DAILY_RECORD_ID_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void deleteDailyRecordById_mapsUnexpectedTransactionFailureTo500() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(timelineDeletionService).deleteDailyRecord(any(), any(), any());

        mockMvc.perform(delete(DAILY_RECORD_ID_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.code").value(-500));
    }

    @Test
    void deleteDailyRecordByDate_returns200WithEmptyBody() throws Exception {
        mockMvc.perform(delete(DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        verify(timelineDeletionService).deleteDailyRecordByDate("v1", SUBJECT_ID, RECORD_DATE);
    }

    @Test
    void deleteDailyRecordByDate_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND))
                .when(timelineDeletionService).deleteDailyRecordByDate(any(), any(), any(LocalDate.class));

        mockMvc.perform(delete(DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void deleteDailyRecordByDate_withInvalidDateReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(delete(INVALID_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineDeletionService);
    }

    @Test
    void deleteDailyRecordByDate_withoutAuthenticationReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(delete(DAILY_RECORD_DATE_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineDeletionService);
    }

    @Test
    void saveDailyRecord_returns200WithEmptyBodyAndPassesPrincipalAndEmotion() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        verify(timelineSaveService).save("v1", SUBJECT_ID, RECORD_DATE, EmotionType.HAPPY);
    }

    @Test
    void saveDailyRecord_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND))
                .when(timelineSaveService).save(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void saveDailyRecord_mapsAlreadySavedTo409() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED))
                .when(timelineSaveService).save(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1003));
    }

    @Test
    void saveDailyRecord_withInvalidDateReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(DAILY_RECORDS_PATH + "/not-a-date/save").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_withoutAuthenticationReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_zeroByteBodyWithoutContentTypeReturns400WithoutCallingService() throws Exception {
        // zero-byte body는 Content-Type이 없어도 415가 아니라 "required body 누락" 400이다.
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_zeroByteJsonBodyReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_missingEmotionTypeReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_explicitNullEmotionTypeReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_unsupportedEmotionLiteralReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":\"ANGRY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_malformedJsonReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_bodyWithoutContentTypeReturns415WithoutCallingService() throws Exception {
        // body는 있는데 Content-Type이 없으면 octet-stream 취급이라 415/-415다(zero-byte 400과 구분).
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID)).content(SAVE_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));

        verifyNoInteractions(timelineSaveService);
    }

    @Test
    void saveDailyRecord_nonJsonContentTypeReturns415WithoutCallingService() throws Exception {
        mockMvc.perform(post(SAVE_DAILY_RECORD_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.TEXT_PLAIN).content(SAVE_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));

        verifyNoInteractions(timelineSaveService);
    }

    // --- updateDailyRecordEmotion (SAVED 감정 수정) ---

    @Test
    void updateDailyRecordEmotion_returns200WithExplicitNullBodyAndPassesPrincipalAndEmotion() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        verify(dailyRecordEmotionUpdateService).updateEmotion("v1", SUBJECT_ID, RECORD_DATE, EmotionType.HAPPY);
    }

    @Test
    void updateDailyRecordEmotion_mapsDraftRejectionTo409With1020() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_SAVED))
                .when(dailyRecordEmotionUpdateService).updateEmotion(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1020));
    }

    @Test
    void updateDailyRecordEmotion_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND))
                .when(dailyRecordEmotionUpdateService).updateEmotion(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void updateDailyRecordEmotion_withInvalidDateReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(put(DAILY_RECORDS_PATH + "/not-a-date/emotion").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_withoutAuthenticationReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_zeroByteBodyWithoutContentTypeReturns400WithoutCallingService() throws Exception {
        // zero-byte body는 Content-Type이 없어도 415가 아니라 "required body 누락" 400이다(save와 같은 계약).
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_zeroByteJsonBodyReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_missingEmotionTypeReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_explicitNullEmotionTypeReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_unsupportedEmotionLiteralReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":\"ANGRY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_numericEmotionTypeReturns400WithoutCallingService() throws Exception {
        // Jackson 기본 coercion은 숫자를 enum ordinal로 받아들인다 — 문자열 literal 계약이라 400이다.
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_malformedJsonReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emotionType\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_bodyWithoutContentTypeReturns415WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID)).content(SAVE_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    @Test
    void updateDailyRecordEmotion_nonJsonContentTypeReturns415WithoutCallingService() throws Exception {
        mockMvc.perform(put(EMOTION_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.TEXT_PLAIN).content(SAVE_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));

        verifyNoInteractions(dailyRecordEmotionUpdateService);
    }

    // --- createTimelineEvent (수동 Event 생성) ---

    private TimelineEventResponse createdManualEvent() {
        return new TimelineEventResponse(
                99L, TimelineEventType.REST,
                LocalDateTime.parse("2026-07-08T14:00:00"), LocalDateTime.parse("2026-07-08T15:00:00"),
                "카페에서 휴식", "성수동", null, null, null, "책을 읽었다.", List.of());
    }

    @Test
    void createTimelineEvent_returns200WithCreatedIdAndNullAiFieldsAndEmptyItems() throws Exception {
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(createdManualEvent());

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.timelineEventId").value(99))
                .andExpect(jsonPath("$.body.eventType").value("REST"))
                .andExpect(jsonPath("$.body.title").value("카페에서 휴식"))
                .andExpect(jsonPath("$.body.memo").value("책을 읽었다."))
                .andExpect(jsonPath("$.body.items").isArray())
                .andExpect(jsonPath("$.body.items").isEmpty())
                // 수동 Event의 AI 결과 전용 읽기 필드는 명시적 JSON null이다.
                .andExpect(result -> {
                    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                            .get("body");
                    for (String field : List.of("question", "place", "address")) {
                        assertThat(body.has(field)).isTrue();
                        assertThat(body.get(field).isNull()).isTrue();
                    }
                });

        ArgumentCaptor<CreateTimelineEventRequest> request =
                ArgumentCaptor.forClass(CreateTimelineEventRequest.class);
        verify(timelineEventCreateService)
                .createEvent(eq("v1"), eq(SUBJECT_ID), eq(RECORD_DATE), request.capture());
        assertThat(request.getValue().eventType()).isEqualTo(TimelineEventType.REST);
        assertThat(request.getValue().title()).isEqualTo("카페에서 휴식");
        assertThat(request.getValue().subtitle()).isEqualTo("성수동");
        assertThat(request.getValue().startAt()).isEqualTo(LocalDateTime.parse("2026-07-08T14:00:00"));
        assertThat(request.getValue().endAt()).isEqualTo(LocalDateTime.parse("2026-07-08T15:00:00"));
        assertThat(request.getValue().memo()).isEqualTo("책을 읽었다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventType", "title", "subtitle", "startAt", "endAt"})
    void createTimelineEvent_missingRequiredKeyRejected400(String missingKey) throws Exception {
        // 5개 키 모두 필수 계약: 키 누락은 역직렬화 단계에서 400 — 서비스에 도달하지 않는다.
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.remove(missingKey);

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_explicitNullEventTypeRejected400WithoutCallingService() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.putNull("eventType");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_unsupportedEventTypeLiteralRejected400WithoutCallingService() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.put("eventType", "PICNIC");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_numericEventTypeRejected400WithoutCallingService() throws Exception {
        // Jackson 기본 coercion은 숫자를 enum ordinal로 받아들인다 — 문자열 literal 계약이라 400이다.
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.put("eventType", 1);

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_invalidTimeFormatRejected400WithoutCallingService() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.put("startAt", "not-a-time");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_explicitNullSubtitleAndEndAtArePassedToService() throws Exception {
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(createdManualEvent());
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.putNull("subtitle");
        body.putNull("endAt");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        ArgumentCaptor<CreateTimelineEventRequest> request =
                ArgumentCaptor.forClass(CreateTimelineEventRequest.class);
        verify(timelineEventCreateService)
                .createEvent(eq("v1"), eq(SUBJECT_ID), eq(RECORD_DATE), request.capture());
        assertThat(request.getValue().subtitle()).isNull();
        assertThat(request.getValue().endAt()).isNull();
    }

    @Test
    void createTimelineEvent_missingOrExplicitNullMemoIsAccepted() throws Exception {
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(createdManualEvent());
        ObjectNode withoutMemo = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        withoutMemo.remove("memo");
        ObjectNode nullMemo = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        nullMemo.putNull("memo");

        for (ObjectNode body : List.of(withoutMemo, nullMemo)) {
            mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                            .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.code").value(0));
        }

        ArgumentCaptor<CreateTimelineEventRequest> request =
                ArgumentCaptor.forClass(CreateTimelineEventRequest.class);
        verify(timelineEventCreateService, org.mockito.Mockito.times(2))
                .createEvent(eq("v1"), eq(SUBJECT_ID), eq(RECORD_DATE), request.capture());
        assertThat(request.getAllValues()).allSatisfy(parsed -> assertThat(parsed.memo()).isNull());
    }

    @Test
    void createTimelineEvent_mapsIllegalArgumentTo400() throws Exception {
        doThrow(new IllegalArgumentException("endAt is before startAt"))
                .when(timelineEventCreateService).createEvent(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void createTimelineEvent_mapsNotFoundTo404() throws Exception {
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND))
                .when(timelineEventCreateService).createEvent(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void createTimelineEvent_withInvalidDateReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post(DAILY_RECORDS_PATH + "/not-a-date/events").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_withoutAuthenticationReturns401WithoutCallingService() throws Exception {
        mockMvc.perform(post(CREATE_EVENT_DATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_bodyWithoutContentTypeReturns415WithoutCallingService() throws Exception {
        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID)).content(CREATE_EVENT_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_nonJsonContentTypeReturns415WithoutCallingService() throws Exception {
        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.TEXT_PLAIN).content(CREATE_EVENT_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));

        verifyNoInteractions(timelineEventCreateService);
    }

    // --- createTimelineEvent photosToAdd (#361) ---

    @Test
    void createTimelineEvent_missingPhotosToAddKeyPassesEmptyList() throws Exception {
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(createdManualEvent());

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        ArgumentCaptor<CreateTimelineEventRequest> request =
                ArgumentCaptor.forClass(CreateTimelineEventRequest.class);
        verify(timelineEventCreateService)
                .createEvent(eq("v1"), eq(SUBJECT_ID), eq(RECORD_DATE), request.capture());
        assertThat(request.getValue().photosToAdd()).isEmpty();
    }

    @Test
    void createTimelineEvent_parsesPhotosToAddAndIgnoresAiAndServerOnlyPayloadFields() throws Exception {
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(createdManualEvent());
        String body = """
                {
                  "eventType": "REST",
                  "title": "카페에서 휴식",
                  "subtitle": "성수동",
                  "startAt": "2026-07-08T14:00:00",
                  "endAt": "2026-07-08T15:00:00",
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

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateTimelineEventRequest> request =
                ArgumentCaptor.forClass(CreateTimelineEventRequest.class);
        verify(timelineEventCreateService)
                .createEvent(eq("v1"), eq(SUBJECT_ID), eq(RECORD_DATE), request.capture());
        CreateTimelineEventRequest parsed = request.getValue();
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
        assertThat(objectMapper.valueToTree(parsed.photosToAdd().get(0).payload()).has("description")).isFalse();
        assertThat(objectMapper.valueToTree(parsed.photosToAdd().get(0).payload()).has("photoUrl")).isFalse();
    }

    @Test
    void createTimelineEvent_emptyPhotosToAddArrayIsAcceptedAsNoPhotos() throws Exception {
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(createdManualEvent());
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.putArray("photosToAdd");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        ArgumentCaptor<CreateTimelineEventRequest> request =
                ArgumentCaptor.forClass(CreateTimelineEventRequest.class);
        verify(timelineEventCreateService)
                .createEvent(eq("v1"), eq(SUBJECT_ID), eq(RECORD_DATE), request.capture());
        assertThat(request.getValue().photosToAdd()).isEmpty();
    }

    @Test
    void createTimelineEvent_explicitNullPhotosToAddRejected400WithoutCallingService() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.putNull("photosToAdd");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_nonArrayPhotosToAddRejected400WithoutCallingService() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(CREATE_EVENT_BODY);
        body.put("photosToAdd", "not-an-array");

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verifyNoInteractions(timelineEventCreateService);
    }

    @Test
    void createTimelineEvent_mapsPhotoCountExceededTo400With1004() throws Exception {
        doThrow(new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, 20))
                .when(timelineEventCreateService).createEvent(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-1004));
    }

    @Test
    void createTimelineEvent_mapsPhotoDeleteInProgressTo409With1019() throws Exception {
        doThrow(new BusinessException(ExceptionType.PHOTO_DELETE_IN_PROGRESS))
                .when(timelineEventCreateService).createEvent(any(), any(), any(LocalDate.class), any());

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1019));
    }

    @Test
    void createTimelineEvent_exposesServiceReturnedItemsInResponse() throws Exception {
        TimelineItemResponse createdPhoto = new TimelineItemResponse(
                21L, ItemType.PHOTO, "0190a1b2-0001-7000-8000-000000000001",
                LocalDateTime.parse("2026-07-08T14:05:00"), null,
                objectMapper.valueToTree(new PhotoPayload(
                        "0190a1b2-0002-7000-8000-000000000002.jpg", "content://media/1001", 37.5665, 126.978,
                        null, null, null, "https://cdn.example/u.jpg")));
        TimelineEventResponse withItems = new TimelineEventResponse(
                99L, TimelineEventType.REST,
                LocalDateTime.parse("2026-07-08T14:00:00"), LocalDateTime.parse("2026-07-08T15:00:00"),
                "카페에서 휴식", "성수동", null, null, null, "책을 읽었다.", List.of(createdPhoto));
        when(timelineEventCreateService.createEvent(any(), any(), any(LocalDate.class), any()))
                .thenReturn(withItems);

        mockMvc.perform(post(CREATE_EVENT_DATE_PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_EVENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.timelineEventId").value(99))
                .andExpect(jsonPath("$.body.items[0].timelineItemId").value(21))
                .andExpect(jsonPath("$.body.items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.body.items[0].payload.photoUrl").value("https://cdn.example/u.jpg"));
    }

    private void assertBodyIsExplicitNull(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.has("body")).isTrue();
        assertThat(response.get("body").isNull()).isTrue();
    }
}
