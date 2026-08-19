package com.laimory.server.push.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.PushComplianceClass;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.service.PushSettingService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 푸시 설정 컨트롤러 슬라이스(MockMvc) — 여섯 경로 매핑, 인증 게이트(401), envelope, 동의 gate 409,
 * 시각 형식 400 매핑을 검증한다. 인프라 0.
 */
@WebMvcTest(PushSettingController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PushSettingControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String BASE = "/a/api/v1/push-settings";
    private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PushSettingService pushSettingService;

    @MockitoBean
    private SubjectMappingService subjectMappingService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    private static NotificationConsentEvent event() {
        NotificationConsentEvent event = NotificationConsentEvent.of(SUBJECT_ID,
                NotificationConsentType.ADVERTISING_PUSH, NotificationConsentAction.CONSENT, 100L,
                LocalDateTime.of(2026, 7, 21, 14, 0), "라이모리 주식회사",
                NotificationConsentProcessingResult.APPLIED, NotificationConsentSource.PUSH_SETTINGS);
        ReflectionTestUtils.setField(event, "notificationConsentEventId", 9L);
        return event;
    }

    @Test
    void unauthenticatedRequests_rejected401BeforeService() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(put(BASE + "/enabled").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/advertising-consent").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(pushSettingService);
    }

    @Test
    void getSettings_returnsServerState() throws Exception {
        when(pushSettingService.getSettings("v1", SUBJECT_ID)).thenReturn(new PushSettingsResponse(
                true,
                new PushSettingsResponse.DailyReminder(false, "21:00", PushComplianceClass.ADVERTISING),
                new PushSettingsResponse.ConsentStatus(false, null),
                new PushSettingsResponse.ConsentStatus(false, null),
                List.of()));

        mockMvc.perform(get(BASE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.pushEnabled").value(true))
                .andExpect(jsonPath("$.body.dailyReminder.enabled").value(false))
                .andExpect(jsonPath("$.body.dailyReminder.time").value("21:00"))
                .andExpect(jsonPath("$.body.dailyReminder.classification").value("ADVERTISING"))
                .andExpect(jsonPath("$.body.advertisingPushConsent.consented").value(false))
                // 미동의 버전은 key 생략이 아니라 명시적 null이다.
                .andExpect(jsonPath("$.body.advertisingPushConsent.version").doesNotExist())
                .andExpect(jsonPath("$.body.recentConsentResults").isArray());
    }

    @Test
    void updatePushEnabled_passesPrincipalSubject() throws Exception {
        mockMvc.perform(put(BASE + "/enabled").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        verify(pushSettingService).updatePushEnabled("v1", SUBJECT_ID, false);
    }

    @Test
    void updateDailyReminderEnabled_passesPrincipalSubject() throws Exception {
        mockMvc.perform(put(BASE + "/daily-reminder/enabled").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        verify(pushSettingService).updateDailyReminderEnabled("v1", SUBJECT_ID, true);
    }

    @Test
    void updateDailyReminderEnabled_withoutConsent_maps409() throws Exception {
        doThrow(new BusinessException(ExceptionType.NOTIFICATION_CONSENT_REQUIRED))
                .when(pushSettingService).updateDailyReminderEnabled(anyString(), any(UUID.class), any());

        mockMvc.perform(put(BASE + "/daily-reminder/enabled").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-4002));
    }

    @Test
    void updateDailyReminderTime_malformedTime_maps400() throws Exception {
        doThrow(new IllegalArgumentException("time must be in HH:mm format"))
                .when(pushSettingService).updateDailyReminderTime(anyString(), any(UUID.class), any());

        mockMvc.perform(put(BASE + "/daily-reminder/time").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"time\":\"9시\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void updateAdvertisingConsent_returnsProcessingResults() throws Exception {
        when(pushSettingService.applyAdvertisingConsent("v1", SUBJECT_ID, true, "v1"))
                .thenReturn(List.of(event()));

        mockMvc.perform(put(BASE + "/advertising-consent").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consented\":true,\"termVersion\":\"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].eventId").value(9))
                .andExpect(jsonPath("$.body[0].consentType").value("ADVERTISING_PUSH"))
                .andExpect(jsonPath("$.body[0].action").value("CONSENT"))
                .andExpect(jsonPath("$.body[0].processingResult").value("APPLIED"))
                .andExpect(jsonPath("$.body[0].senderName").value("라이모리 주식회사"));
    }

    @Test
    void updateAdvertisingConsent_staleVersion_maps409() throws Exception {
        doThrow(new BusinessException(ExceptionType.STALE_TERM_VERSION))
                .when(pushSettingService).applyAdvertisingConsent(anyString(), any(UUID.class), any(), any());

        mockMvc.perform(put(BASE + "/advertising-consent").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consented\":true,\"termVersion\":\"old\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.code").value(-3002));
    }

    @Test
    void updateNightAdvertisingConsent_reusesSameContract() throws Exception {
        when(pushSettingService.applyNightAdvertisingConsent("v1", SUBJECT_ID, false, null))
                .thenReturn(List.of(event()));

        mockMvc.perform(put(BASE + "/night-advertising-consent").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consented\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].eventId").value(9));
    }

}
