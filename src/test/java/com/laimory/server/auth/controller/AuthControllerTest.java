package com.laimory.server.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.service.AuthTokenService;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Auth 컨트롤러 슬라이스 테스트(MockMvc). 토큰/refresh/logout 성공 envelope와 ERROR_2002/2003 → 401 매핑을 고정한다. 인프라 0. */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    private static final String TOKEN = "/api/v1/auth/token";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthTokenService authTokenService;

    @Test
    void issueTokens_returns200WithTokenPair() throws Exception {
        when(authTokenService.issueTokens(any(), eq("code-1"), eq("verifier-1")))
                .thenReturn(new TokenResponse("access-abc", "refresh-def"));

        mockMvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appCode\":\"code-1\",\"appVerifier\":\"verifier-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.body.accessToken").value("access-abc"))
                .andExpect(jsonPath("$.body.refreshToken").value("refresh-def"));
    }

    @Test
    void issueTokens_businessError2002_returns401Envelope() throws Exception {
        when(authTokenService.issueTokens(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ERROR_2002));

        mockMvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appCode\":\"c\",\"appVerifier\":\"v\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value("ERROR_2002"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void refresh_returns200WithTokenPair() throws Exception {
        when(authTokenService.refresh(any(), eq("refresh-old")))
                .thenReturn(new TokenResponse("access-new", "refresh-new"));

        mockMvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-old\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.body.accessToken").value("access-new"))
                .andExpect(jsonPath("$.body.refreshToken").value("refresh-new"));
    }

    @Test
    void refresh_businessError2003_returns401Envelope() throws Exception {
        when(authTokenService.refresh(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ERROR_2003));

        mockMvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-old\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value("ERROR_2003"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void logout_returns200WithNullBody_andInvokesService() throws Exception {
        mockMvc.perform(post(LOGOUT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-old\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.body").doesNotExist());

        verify(authTokenService).logout(any(), eq("refresh-old"));
    }
}
