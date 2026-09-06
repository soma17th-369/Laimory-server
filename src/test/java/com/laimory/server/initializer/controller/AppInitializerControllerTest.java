package com.laimory.server.initializer.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.initializer.dto.AgreementRequiredTermResponse;
import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.initializer.dto.InitializerTermsResponse;
import com.laimory.server.initializer.service.AppInitializerService;
import com.laimory.server.terms.TermType;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.service.SubjectMappingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 앱 초기화 컨트롤러 슬라이스(MockMvc) — 경로 매핑, 인증 게이트(401), envelope, 두 hidden principal
 * (userId·subjectId) 주입, 온보딩 boolean과 약관 그룹의 명시 직렬화를 검증한다. 인프라 0.
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
    private SubjectMappingService subjectMappingService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void unauthenticatedRequest_rejected401BeforeService() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(appInitializerService);
    }

    @Test
    void getInitializer_passesBothPrincipals_andSerializesAgreementRequiredList() throws Exception {
        // 서비스 stub이 (v1, USER_ID, SUBJECT_ID)에만 응답하므로 두 hidden principal 주입까지 함께 고정된다.
        when(appInitializerService.getInitialState("v1", USER_ID, SUBJECT_ID))
                .thenReturn(new InitializerResponse(true, new InitializerTermsResponse(List.of(
                        new AgreementRequiredTermResponse(TermType.TERMS_OF_SERVICE, "1.1")))));

        mockMvc.perform(get(BASE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.body.terms.agreementRequired.length()").value(1))
                .andExpect(jsonPath("$.body.terms.agreementRequired[0].termType").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.body.terms.agreementRequired[0].version").value("1.1"));
    }

    @Test
    void getInitializer_serializesFalseAndEmptyListAsExplicitKeys() throws Exception {
        // false·빈 배열도 key가 사라지지 않아야 한다 — 앱이 "없음"과 "미완료/불필요"를 구분할 수 없게 되면 안 된다.
        when(appInitializerService.getInitialState("v1", USER_ID, SUBJECT_ID))
                .thenReturn(new InitializerResponse(false, new InitializerTermsResponse(List.of())));

        mockMvc.perform(get(BASE).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.onboardingCompleted").exists())
                .andExpect(jsonPath("$.body.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.body.terms.agreementRequired").isArray())
                .andExpect(jsonPath("$.body.terms.agreementRequired").isEmpty());
    }
}
