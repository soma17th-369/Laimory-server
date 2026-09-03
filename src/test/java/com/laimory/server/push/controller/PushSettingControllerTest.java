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

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.push.service.PushSettingService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 푸시 설정 컨트롤러 슬라이스(MockMvc) — 세 경로 매핑, 인증 게이트(401), envelope, 잘못된 body의 400
 * 매핑을 검증한다. 인프라 0.
 */
@WebMvcTest(PushSettingController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PushSettingControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String BASE = "/a/api/v1/push-settings";

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

    @Test
    void unauthenticatedRequests_rejected401BeforeService() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(put(BASE + "/enabled").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(pushSettingService);
    }

    @Test
    void getSettings_returnsServerState() throws Exception {
        when(pushSettingService.getSettings("v1", SUBJECT_ID)).thenReturn(new PushSettingsResponse(
                true,
                new PushSettingsResponse.DailyReminder(false, "21:00")));

        mockMvc.perform(get(BASE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.pushEnabled").value(true))
                .andExpect(jsonPath("$.body.dailyReminder.enabled").value(false))
                .andExpect(jsonPath("$.body.dailyReminder.time").value("21:00"));
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
    void updateDailyReminderEnabled_missingEnabled_maps400() throws Exception {
        // 시각 변경 API가 사라진 뒤 이 endpoint가 유일한 사용자 조작이다 — body 누락이 500이 아니라
        // -400 envelope으로 나가는지 고정한다.
        doThrow(new IllegalArgumentException("enabled is required"))
                .when(pushSettingService).updateDailyReminderEnabled(anyString(), any(UUID.class), any());

        mockMvc.perform(put(BASE + "/daily-reminder/enabled").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }
}
