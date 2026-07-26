package com.laimory.server.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.logging.RequestLogAttributes;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.controller.TimelineController;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 전역 예외 → ApiResponse envelope 변환 계약 검증(슬라이스, 인프라 0).
 *
 * <p>여기의 404/405/415/깨진JSON 케이스는 §설계의 필수 검증이다 — ResponseEntityExceptionHandler
 * 상속이 해당 예외를 정말 잡아 envelope로 바꾸는지 고정한다(깨지면 개별 @ExceptionHandler 추가로 보수).
 */
@WebMvcTest(TimelineController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class GlobalExceptionHandlerTest {

    private static final long USER_ID = 7L;
    private static final String TASKS = "/a/api/v1/timeline/drafts";
    private static final String VALID_BODY = """
            {"recordDate": "2026-06-17", "recordAt": "2026-06-18T09:30:00", "recordTimeZone": "Asia/Seoul",
             "timelineWindow": {"startTime": "2026-06-17T00:00", "endTime": "2026-06-18T00:00"},
             "sourceItems": [{"itemType": "PHOTO", "startAt": "2026-06-17T09:00:00", "endAt": null,
               "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://x",
                           "latitude": 1.0, "longitude": 2.0}}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TimelineDraftTaskService timelineDraftTaskService;
    @MockitoBean
    private TimelineDraftTaskPollingService timelineDraftTaskPollingService;
    @MockitoBean
    private PhotoUploadService photoUploadService;

    @Test
    void businessException_mapsToEnumStatus_withCodeAndTransactionIdHeader() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-1003))
                .andExpect(jsonPath("$.header.message").isNotEmpty())
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.header.transactionId").doesNotExist()) // 노출 채널은 헤더뿐(hard cut 회귀 방지)
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void illegalArgument_mapsToError0400_andForwardsDetailToAccessLog() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("recordAt is required"));

        var result = mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andReturn();

        // access 로그 합류 계약: 내부 타입·검증 메시지가 attribute로 전달된다(레벨·필드 조립은 필터 몫)
        assertThat(result.getRequest().getAttribute(RequestLogAttributes.EXCEPTION_TYPE))
                .isEqualTo(ExceptionType.VALIDATION_FAILED);
        assertThat(result.getRequest().getAttribute(RequestLogAttributes.ERROR_DETAIL))
                .isEqualTo("recordAt is required");
    }

    @Test
    void illegalArgument_detailIsSanitized_noCrlfAndBounded() throws Exception {
        // 요청값이 echo된 긴 메시지 — CR/LF 제거(텍스트 로그 위조 방지) + 200자 상한(keyword term 한도로
        // 인한 ES 문서 거부 방지, ignore_above 256과 이중 방어)이 단일 조립 지점에서 적용돼야 한다.
        String hostile = "invalid photo filename: line1\r\nFAKE LOG LINE\n" + "x".repeat(500);
        when(timelineDraftTaskService.createDraftTask(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(hostile));

        var result = mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andReturn();

        String detail = (String) result.getRequest().getAttribute(RequestLogAttributes.ERROR_DETAIL);
        assertThat(detail).doesNotContain("\r").doesNotContain("\n");
        assertThat(detail.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void unexpectedException_mapsToError0500_withoutLeakingDetail() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis serialization failed: secret detail"));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.code").value(-500))
                .andExpect(jsonPath("$.header.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret")))); // 내부 상세 비노출
    }

    @Test
    void unmappedPath_returns404Envelope() throws Exception {
        mockMvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andExpect(header().exists("Transaction-Id"));
    }

    @Test
    void wrongMethod_returns405Envelope_withAllowHeader() throws Exception {
        mockMvc.perform(delete(TASKS).with(authenticatedUser(USER_ID)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow")) // ResponseEntityExceptionHandler가 보존
                .andExpect(jsonPath("$.header.code").value(-405));
    }

    @Test
    void missingContentType_returns415Envelope() throws Exception {
        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value(-415));
    }

    @Test
    void malformedJson_returns400Envelope() throws Exception {
        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void acceptLanguage_switchesErrorMessageLocale() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header("Accept-Language", "en"))
                .andExpect(jsonPath("$.header.message").value("The daily record has already been saved."));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.header.message").value("이미 저장된 하루 기록입니다."));
    }

    /**
     * Accept-Language가 없으면 한국어(계약상 폴백). spring.web.locale=ko가 없으면 서버 JVM 로캘(EC2=en,
     * MockMvc 기본=ENGLISH)로 새어 영어가 나가는 회귀를 잡는다.
     */
    @Test
    void withoutAcceptLanguage_fallsBackToKorean() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        mockMvc.perform(post(TASKS).with(authenticatedUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(jsonPath("$.header.message").value("이미 저장된 하루 기록입니다."));
    }
}
