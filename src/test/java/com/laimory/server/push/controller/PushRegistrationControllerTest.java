package com.laimory.server.push.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.push.service.PushRegistrationService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.user.SubjectMappingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * FID 등록 컨트롤러 슬라이스 테스트(MockMvc). 경로 매핑(PUT/DELETE)·인증 게이트(401)·envelope·
 * 400 매핑과 "userId는 인증 principal에서 서비스로 전달" 계약을 검증한다. 인프라 0.
 */
@WebMvcTest(PushRegistrationController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class PushRegistrationControllerTest {

    private static final long USER_ID = 7L;
    private static final SubjectId SUBJECT_ID = TestSubjects.id(USER_ID);
    private static final String PATH = "/a/api/v1/push-registrations";
    private static final String BODY = """
            {"firebaseInstallationId":"fid-abc"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PushRegistrationService pushRegistrationService;

    @MockitoBean
    private SubjectMappingService subjectMappingService;

    @BeforeEach
    void resolveSubject() {
        org.mockito.Mockito.when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void unauthenticatedRequests_rejected401BeforeService() throws Exception {
        // 인증 게이트: 무인증 요청은 컨트롤러/서비스에 도달하지 못하고 401 ERROR_2001 envelope로 거절된다.
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
        mockMvc.perform(delete(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(pushRegistrationService);
    }

    @Test
    void register_returns200WithEmptyBody_andPassesPrincipalUserId() throws Exception {
        mockMvc.perform(put(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        // userId는 클라 입력이 아니라 인증 principal이고, FID는 body 원문 그대로 서비스에 전달된다.
        verify(pushRegistrationService).register("v1", SUBJECT_ID, "fid-abc");
    }

    @Test
    void unregister_returns200WithEmptyBody_andPassesPrincipalUserId() throws Exception {
        mockMvc.perform(delete(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(header().exists("Transaction-Id"))
                .andExpect(this::assertBodyIsExplicitNull);

        verify(pushRegistrationService).unregister("v1", SUBJECT_ID, "fid-abc");
    }

    @Test
    void register_mapsIllegalArgumentTo400() throws Exception {
        doThrow(new IllegalArgumentException("firebaseInstallationId is required"))
                .when(pushRegistrationService).register(any(), any(SubjectId.class), any());

        mockMvc.perform(put(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firebaseInstallationId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void unregister_mapsIllegalArgumentTo400() throws Exception {
        doThrow(new IllegalArgumentException("firebaseInstallationId is required"))
                .when(pushRegistrationService).unregister(any(), any(SubjectId.class), any());

        mockMvc.perform(delete(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void register_missingField_passesNullToServiceValidation() throws Exception {
        // 필드 부재는 역직렬화에서 null — 400 판정은 서비스 validation 한 곳이 담당한다(중복 검증 금지).
        doThrow(new IllegalArgumentException("firebaseInstallationId is required"))
                .when(pushRegistrationService).register(anyString(), any(SubjectId.class), any());

        mockMvc.perform(put(PATH).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));

        verify(pushRegistrationService).register("v1", SUBJECT_ID, null);
    }

    private void assertBodyIsExplicitNull(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.has("body")).isTrue();
        assertThat(response.get("body").isNull()).isTrue();
    }
}
