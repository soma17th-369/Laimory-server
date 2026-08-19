package com.laimory.server.push.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.service.PushOptOutService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 비로그인 수신거부 컨트롤러 슬라이스(MockMvc) — bearer 없이 접근 가능한 public 경로와 credential 실패의
 * 단일 401 응답을 검증한다. 인프라 0.
 */
@WebMvcTest(PushOptOutController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PushOptOutControllerTest {

    private static final String PATH = "/api/v1/push-opt-outs";
    private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String BODY = """
            {"firebaseInstallationId":"fid-1","optOutToken":"token-value"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PushOptOutService pushOptOutService;

    private static NotificationConsentEvent event(NotificationConsentType type) {
        NotificationConsentEvent event = NotificationConsentEvent.of(TestSubjects.id(71L), type,
                NotificationConsentAction.WITHDRAW, 100L, LocalDateTime.of(2026, 7, 21, 14, 0),
                "라이모리 주식회사", NotificationConsentProcessingResult.APPLIED,
                NotificationConsentSource.INSTALLATION_OPT_OUT);
        ReflectionTestUtils.setField(event, "notificationConsentEventId", 3L);
        return event;
    }

    @Test
    void optOut_worksWithoutBearerToken() throws Exception {
        when(pushOptOutService.optOut("v1", "fid-1", "token-value"))
                .thenReturn(List.of(event(NotificationConsentType.ADVERTISING_PUSH),
                        event(NotificationConsentType.NIGHT_ADVERTISING_PUSH)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                // 일반 철회가 야간 철회를 동반하면 처리결과가 두 건이다.
                .andExpect(jsonPath("$.body.length()").value(2))
                .andExpect(jsonPath("$.body[0].action").value("WITHDRAW"))
                .andExpect(jsonPath("$.body[1].consentType").value("NIGHT_ADVERTISING_PUSH"));

        verify(pushOptOutService).optOut("v1", "fid-1", "token-value");
    }

    @Test
    void invalidCredential_maps401WithSharedCode() throws Exception {
        doThrow(new BusinessException(ExceptionType.PUSH_OPT_OUT_TOKEN_INVALID))
                .when(pushOptOutService).optOut(anyString(), any(), any());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-4001))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

}
