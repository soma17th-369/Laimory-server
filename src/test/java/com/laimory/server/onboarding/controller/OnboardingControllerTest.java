package com.laimory.server.onboarding.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.onboarding.service.OnboardingService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 온보딩 완료 컨트롤러 슬라이스(MockMvc) — body 없는 POST 매핑, 인증 게이트(401), 반복 호출의 멱등
 * 200/body=null envelope을 검증한다. 인프라 0.
 */
@WebMvcTest(OnboardingController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class OnboardingControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String COMPLETE = "/a/api/v1/onboarding/complete";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnboardingService onboardingService;

    @MockitoBean
    private SubjectMappingService subjectMappingService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void unauthenticatedRequest_rejected401BeforeService() throws Exception {
        mockMvc.perform(post(COMPLETE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(onboardingService);
    }

    @Test
    void complete_withoutBody_returnsEmptySuccessEnvelope() throws Exception {
        // request body가 없다 — 대상은 인증 subject 자신이고 바꿀 값도 하나뿐이다.
        mockMvc.perform(post(COMPLETE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body").doesNotExist());

        verify(onboardingService).completeOnboarding("v1", SUBJECT_ID);
    }

    @Test
    void complete_repeatedCall_staysIdempotentSuccess() throws Exception {
        mockMvc.perform(post(COMPLETE).with(authenticatedUser(USER_ID))).andExpect(status().isOk());
        mockMvc.perform(post(COMPLETE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        verify(onboardingService, times(2)).completeOnboarding("v1", SUBJECT_ID);
    }
}
