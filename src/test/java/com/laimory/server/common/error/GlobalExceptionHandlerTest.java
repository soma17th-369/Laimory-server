package com.laimory.server.common.error;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.timeline.controller.TimelineController;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
class GlobalExceptionHandlerTest {

    private static final String TASKS = "/a/api/v1/timeline/drafts";
    private static final String VALID_BODY = """
            {"recordAt": "2026-06-17T12:00:00", "recordTimeZone": "Asia/Seoul",
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
    void businessException_mapsToEnumStatus_withCodeAndTransactionId() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ERROR_1003));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value("ERROR_1003"))
                .andExpect(jsonPath("$.header.message").isNotEmpty())
                .andExpect(jsonPath("$.header.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void illegalArgument_mapsToCommon4000() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("recordAt is required"));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("COMMON_4000"));
    }

    @Test
    void unexpectedException_mapsToCommon5000_withoutLeakingDetail() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis serialization failed: secret detail"));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.header.code").value("COMMON_5000"))
                .andExpect(jsonPath("$.header.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret")))); // 내부 상세 비노출
    }

    @Test
    void unmappedPath_returns404Envelope() throws Exception {
        mockMvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value("COMMON_4040"))
                .andExpect(jsonPath("$.header.transactionId").isNotEmpty());
    }

    @Test
    void wrongMethod_returns405Envelope_withAllowHeader() throws Exception {
        mockMvc.perform(delete(TASKS))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow")) // ResponseEntityExceptionHandler가 보존
                .andExpect(jsonPath("$.header.code").value("COMMON_4050"));
    }

    @Test
    void missingContentType_returns415Envelope() throws Exception {
        mockMvc.perform(post(TASKS).contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.code").value("COMMON_4150"));
    }

    @Test
    void malformedJson_returns400Envelope() throws Exception {
        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value("COMMON_4000"));
    }

    @Test
    void acceptLanguage_switchesErrorMessageLocale() throws Exception {
        when(timelineDraftTaskService.createDraftTask(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ERROR_1003));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header("Accept-Language", "en"))
                .andExpect(jsonPath("$.header.message").value("The daily record has already been saved."));

        mockMvc.perform(post(TASKS).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.header.message").value("이미 저장된 하루 기록입니다."));
    }
}
