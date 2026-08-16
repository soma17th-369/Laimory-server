package com.laimory.server.timeline.controller;

import static org.hamcrest.Matchers.nullValue;
import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static com.laimory.server.testsupport.TestSubjects.id;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.dto.TimelineWindowDto;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.dto.PhotoUploadResponse;
import com.laimory.server.timeline.dto.DraftTaskListResponse;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDraftTaskListService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.terms.service.TermsEnforcementService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.user.SubjectMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공개 컨트롤러 슬라이스 테스트(MockMvc). 상태 매핑(202/400/409/404)을 검증한다. 인프라 0.
 */
@WebMvcTest(TimelineController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TimelineControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = id(USER_ID);
    private static final String TASKS = "/a/api/v1/timeline/drafts";

    // recordDate(선택 날짜)와 recordAt(실제 작성 시각)의 날짜가 다른 "다음날 아침 일기" 시나리오 — 정합성 미검증 계약.
    private static final String CREATE_BODY = """
            {
              "recordDate": "2026-06-17",
              "recordAt": "2026-06-18T09:30:00",
              "recordTimeZone": "Asia/Seoul",
              "timelineWindow": {"startTime": "2026-06-17T00:00", "endTime": "2026-06-18T00:00"},
              "sourceItems": [
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000031",
                 "startAt": "2026-06-17T09:00:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://x",
                             "latitude": 1.0, "longitude": 2.0}}
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
    @MockitoBean
    private TimelineDraftTaskListService timelineDraftTaskListService;
    @MockitoBean
    private PhotoUploadService photoUploadService;
    @MockitoBean
    private SubjectMappingService subjectMappingService;
    // 약관 gate interceptor(#303)가 슬라이스에도 적용된다 — 기본 no-op mock이면 gate 통과.
    @MockitoBean
    private TermsEnforcementService termsEnforcementService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void protectedEndpoints_withoutAuthentication_return401Envelope() throws Exception {
        // 인증 게이트(T3 포함): 무인증 요청은 컨트롤러/서비스에 도달하지 못하고 401 ERROR_2001 envelope로 거절된다.
        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());
        mockMvc.perform(get(TASKS + "/t-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(get(TASKS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        org.mockito.Mockito.verifyNoInteractions(
                timelineDraftTaskService, timelineDraftTaskPollingService, timelineDraftTaskListService,
                photoUploadService);
    }

    @Test
    void createDraftTask_returns202WithTaskId() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any())).thenReturn("task-123");

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.taskId").value("task-123"));
    }

    @Test
    void createDraftTask_passesParsedRecordDateAndWindowToService() throws Exception {
        // HTTP 파싱 계약 고정: recordDate는 ISO LocalDate, window는 offset 없는 ISO local datetime으로 파싱돼
        // 값 그대로 서비스에 전달된다(recordAt과 recordDate의 날짜가 달라도 그대로 — 정합성 미검증).
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any())).thenReturn("task-123");

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isAccepted());

        verify(timelineDraftTaskService).createDraftTask(eq("v1"), eq(SUBJECT_ID), eq(LocalDate.parse("2026-06-17")),
                eq(LocalDateTime.parse("2026-06-18T09:30:00")), eq("Asia/Seoul"),
                eq(new TimelineWindowDto(LocalDateTime.parse("2026-06-17T00:00"),
                        LocalDateTime.parse("2026-06-18T00:00"))), any());
    }

    @Test
    void createDraftTask_mapsIllegalArgumentTo400() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("recordDate is required"));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void createDraftTask_mapsSavedConflictTo409() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1003));
    }

    @Test
    void createDraftTask_mapsAllItemsAlreadySavedConflictTo409() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.APPEND_NO_NEW_ITEMS));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1013));
    }

    @ParameterizedTest
    @CsvSource({"GEOCODING_TRANSIENT_FAILURE, -1014", "GEOCODING_PERMANENT_FAILURE, -1015"})
    void createDraftTask_mapsGeocodingFailureTo502(String type, int code) throws Exception {
        // 지오코딩 loud fail 계약 회귀 가드(degrade→502 정책 변경 고정): 전이(1014)·영구(1015) 둘 다 502 + 해당 코드 envelope, body=null.
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.valueOf(type)));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.code").value(code))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void createDraftTask_mapsAiDispatchFailureTo502WithoutTaskId() throws Exception {
        // AI dispatch 실패 계약: 502 + -1009 envelope, body=null — 실패 응답에는 내부 taskId가 없다.
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.AI_DISPATCH_FAILED));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.code").value(-1009))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void createPhotoUploads_returns200WithUploads() throws Exception {
        when(photoUploadService.createUploads(any(), any(), any()))
                .thenReturn(new PhotoUploadCreateResponse(List.of(
                        new PhotoUploadResponse("f.jpg", "https://example/put"))));

        String body = """
                {"photos": [{"contentType": "image/jpeg", "size": 1024}]}
                """;
        mockMvc.perform(post(TASKS + "/photo-uploads").with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.uploads[0].filename").value("f.jpg"))
                .andExpect(jsonPath("$.body.uploads[0].uploadUrl").value("https://example/put"));

        // principal userId가 service 인자로 전달된다(고정 0 회귀 방지).
        verify(photoUploadService).createUploads(eq("v1"), eq(SUBJECT_ID), any());
    }

    @Test
    void createPhotoUploads_mapsLimitExceededToDedicatedCodeWithLimitValue() throws Exception {
        when(photoUploadService.createUploads(any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.PHOTO_SIZE_EXCEEDED, 15L));

        String body = """
                {"photos": [{"contentType": "image/jpeg", "size": 99999999}]}
                """;
        mockMvc.perform(post(TASKS + "/photo-uploads").with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-1005))
                .andExpect(jsonPath("$.header.message").value(org.hamcrest.Matchers.containsString("15")));
    }

    @Test
    void createPhotoUploads_mapsIllegalArgumentTo400() throws Exception {
        when(photoUploadService.createUploads(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("too many photos"));

        String body = """
                {"photos": [{"contentType": "image/gif", "size": 1024}]}
                """;
        mockMvc.perform(post(TASKS + "/photo-uploads").with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void listProcessingDraftTasks_returns200WithTaskIdsNewestFirst() throws Exception {
        // T1: 성공 envelope(code 0) + body.taskIds에 진행 중 작업 ID만 최신순 — 상세 필드는 싣지 않는다.
        when(timelineDraftTaskListService.list(any(), any()))
                .thenReturn(new DraftTaskListResponse(List.of("t-newer", "t-older")));

        mockMvc.perform(get(TASKS).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.body.taskIds[0]").value("t-newer"))
                .andExpect(jsonPath("$.body.taskIds[1]").value("t-older"))
                .andExpect(jsonPath("$.body.taskIds.length()").value(2));

        // principal userId가 service 인자로 전달된다 — userId는 path/query/body 입력이 아니다(D16).
        verify(timelineDraftTaskListService).list(eq("v1"), eq(SUBJECT_ID));
    }

    @Test
    void listProcessingDraftTasks_empty_returns200WithEmptyArray() throws Exception {
        // T2: 진행 작업이 없어도 404/null이 아니라 200 + 빈 배열 계약이다.
        when(timelineDraftTaskListService.list(any(), any()))
                .thenReturn(new DraftTaskListResponse(List.of()));

        mockMvc.perform(get(TASKS).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.taskIds").isArray())
                .andExpect(jsonPath("$.body.taskIds").isEmpty());
    }

    @Test
    void listProcessingDraftTasks_redisAuthorityFailure_maps500Envelope() throws Exception {
        // T11: 후보 read·역직렬화 실패는 index만 보고 목록을 만들 수 없다 — catch-all 500(-500)으로 끝난다.
        when(timelineDraftTaskListService.list(any(), any()))
                .thenThrow(new IllegalStateException("TimelineDraftTask 역직렬화에 실패했습니다: t-1"));

        mockMvc.perform(get(TASKS).with(authenticatedUser(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.code").value(-500))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void pollDraftTask_returns200WithStatus() throws Exception {
        when(timelineDraftTaskPollingService.poll(any(), any(), eq("t-1")))
                .thenReturn(DraftTaskStatusResponse.processing(12L));

        mockMvc.perform(get(TASKS + "/t-1").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.status").value("PROCESSING"))
                // PROCESSING 전용: AI 작업 대기 경과 시간(완료된 초)이 숫자로 실린다.
                .andExpect(jsonPath("$.body.elapsedSeconds").value(12));

        // principal userId가 service 인자로 전달된다(고정 0 회귀 방지).
        verify(timelineDraftTaskPollingService).poll(eq("v1"), eq(SUBJECT_ID), eq("t-1"));
    }

    /**
     * FAILED 폴링도 에러가 아니라 성공 envelope다: HTTP 200 + header.code=0, 실제 상태는 body.status.
     * (FAILED를 별도 에러 응답으로 매핑하는 회귀 방지 — error는 body.error에 실패 분류 코드로, result는 null.)
     */
    @Test
    void pollDraftTask_failed_returns200WithEnvelope() throws Exception {
        when(timelineDraftTaskPollingService.poll(any(), any(), eq("t-failed")))
                .thenReturn(DraftTaskStatusResponse.failed(-1008));

        mockMvc.perform(get(TASKS + "/t-failed").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.status").value("FAILED"))
                .andExpect(jsonPath("$.body.error").value(-1008))
                .andExpect(jsonPath("$.body.result").value(nullValue()))
                // 경과 시간은 PROCESSING 전용 — terminal 응답 shape는 바뀌지 않는다(key 생략).
                .andExpect(jsonPath("$.body.elapsedSeconds").doesNotExist());
    }

    @Test
    void pollDraftTask_mapsNotFoundTo404() throws Exception {
        when(timelineDraftTaskPollingService.poll(any(), any(), eq("missing")))
                .thenThrow(new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));

        mockMvc.perform(get(TASKS + "/missing").with(authenticatedUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-1001));
    }

    /**
     * SUCCESS 폴링 응답의 직렬화 계약을 컨트롤러 레벨에서 고정한다(STAGE 0 lockstep):
     * 새 이름(events/timelineEventId/timelineItemId)이 실제 JSON에 나오고, 옛 이름(cards/id)은 없어야 한다.
     */
    @Test
    void pollDraftTask_success_serializesEventContract() throws Exception {
        TimelineItemResponse item = new TimelineItemResponse(
                10L, ItemType.PHOTO, "raw-10",
                LocalDateTime.parse("2026-06-17T09:00:00"), null,
                objectMapper.valueToTree(new PhotoPayload("u", "content://x", 1.0, 2.0, null,
                        "https://cdn.example/u")));
        TimelineEventResponse event = new TimelineEventResponse(
                1L, TimelineEventType.UNKNOWN, LocalDateTime.parse("2026-06-17T09:00:00"), null,
                "title", "subtitle", "question", "memo", List.of(item));
        DailyTimelineResponse result = new DailyTimelineResponse(
                42L, LocalDate.parse("2026-06-17"), DailyRecordStatus.DRAFT, null, List.of(event));
        when(timelineDraftTaskPollingService.poll(any(), any(), eq("t-ok")))
                .thenReturn(DraftTaskStatusResponse.success(result));

        mockMvc.perform(get(TASKS + "/t-ok").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.status").value("SUCCESS"))
                // dailyRecordId는 삭제 API 타깃팅용 결과 식별자 — 응답 직렬화 계약에 포함된다.
                .andExpect(jsonPath("$.body.result.dailyRecordId").value(42))
                // DailyRecord status는 SUCCESS result에도 직렬화된다(#298 — draft 폴링 결과는 DRAFT).
                .andExpect(jsonPath("$.body.result.status").value("DRAFT"))
                .andExpect(jsonPath("$.body.result.events[0].timelineEventId").value(1))
                // eventType은 uppercase literal로 직렬화된다(#166 — UNKNOWN은 분류 미확정 fallback).
                .andExpect(jsonPath("$.body.result.events[0].eventType").value("UNKNOWN"))
                .andExpect(jsonPath("$.body.result.events[0].items[0].timelineItemId").value(10))
                .andExpect(jsonPath("$.body.result.events[0].items[0].itemType").value("PHOTO"))
                .andExpect(jsonPath("$.body.result.events[0].items[0].rawId").value("raw-10"))
                // payload는 저장본 pass-through — photoUrl(서버 주입)과 filename 둘 다 노출된다.
                .andExpect(jsonPath("$.body.result.events[0].items[0].payload.photoUrl").value("https://cdn.example/u"))
                .andExpect(jsonPath("$.body.result.events[0].items[0].payload.filename").value("u"))
                .andExpect(jsonPath("$.body.result.cards").doesNotExist())
                .andExpect(jsonPath("$.body.result.events[0].id").doesNotExist())
                .andExpect(jsonPath("$.body.result.events[0].items[0].id").doesNotExist())
                // 경과 시간은 PROCESSING 전용 — SUCCESS 응답 shape는 바뀌지 않는다(key 생략).
                .andExpect(jsonPath("$.body.elapsedSeconds").doesNotExist());
    }
}
