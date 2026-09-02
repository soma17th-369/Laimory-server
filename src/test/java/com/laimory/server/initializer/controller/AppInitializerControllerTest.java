package com.laimory.server.initializer.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.initializer.service.AppInitializerService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingCache;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 앱 초기화 컨트롤러 슬라이스(MockMvc) — 경로 매핑, 인증 게이트(401), envelope, 두 boolean 값의 명시
 * 직렬화를 검증한다. 인프라 0.
 */
@WebMvcTest(AppInitializerController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class AppInitializerControllerTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String BASE = "/a/api/v1/initializer";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppInitializerService appInitializerService;

    @MockitoBean
    private SubjectMappingCache subjectMappingCache;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingCache.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void unauthenticatedRequest_rejected401BeforeService() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(appInitializerService);
    }

    @Test
    void getInitializer_returnsCompletedTrue_withVersionAndPrincipalSubject() throws Exception {
        when(appInitializerService.getInitialState("v1", SUBJECT_ID))
                .thenReturn(new InitializerResponse(true));

        mockMvc.perform(get(BASE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.onboardingCompleted").value(true));
    }

    @Test
    void getInitializer_serializesFalseAsExplicitKey() throws Exception {
        // false도 key가 사라지지 않아야 한다 — 앱이 "없음"과 "미완료"를 구분할 수 없게 되면 안 된다.
        when(appInitializerService.getInitialState("v1", SUBJECT_ID))
                .thenReturn(new InitializerResponse(false));

        mockMvc.perform(get(BASE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.onboardingCompleted").exists())
                .andExpect(jsonPath("$.body.onboardingCompleted").value(false));
    }
}
