package com.laimory.server.user.controller;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.user.Provider;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.service.UserService;
import com.laimory.server.user.service.UserWithdrawalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 내 회원 정보 컨트롤러 슬라이스 테스트(MockMvc). 경로 매핑(GET/DELETE /me)·인증 게이트(401)·envelope·
 * nullable nickname의 명시적 JSON null·탈퇴 202(body=null)와 "userId는 인증 principal에서 서비스로 전달"
 * 계약을 검증한다. 인프라 0. (hidden principal·bearerAuth 문서 계약은
 * {@code arch.ApiAuthenticationContractTest} 소유.)
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class UserControllerTest {

    private static final long USER_ID = 7L;
    private static final String PATH = "/a/api/v1/user";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserWithdrawalService userWithdrawalService;

    @Test
    void unauthenticatedRequest_rejected401BeforeService() throws Exception {
        // 인증 게이트: 무인증 요청은 컨트롤러/서비스에 도달하지 못하고 401 ERROR_2001 envelope로 거절된다.
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(userService, userWithdrawalService);
    }

    @Test
    void withdraw_unauthenticatedRequest_rejected401BeforeService() throws Exception {
        mockMvc.perform(delete(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(userWithdrawalService);
    }

    @Test
    void withdraw_returns202WithNullBody_andPassesPrincipalUserId() throws Exception {
        // 202 = 탈퇴 transaction commit(논리 탈퇴·credential 차단·삭제 작업 접수) — body는 명시적 JSON null.
        mockMvc.perform(delete(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(header().exists("Transaction-Id"));

        // userId는 클라 입력이 아니라 인증 principal이고, applicationVersion과 함께 서비스로 전달된다.
        verify(userWithdrawalService).withdraw("v1", USER_ID);
    }

    @Test
    void withdraw_missingUserRow_convergesToSame401() throws Exception {
        // 이미 최종 삭제된 회원(행 없음)은 무토큰과 같은 401 -2001로 수렴한다(존재 비노출).
        doThrow(new BusinessException(ExceptionType.API_AUTHENTICATION_REQUIRED))
                .when(userWithdrawalService).withdraw("v1", USER_ID);

        mockMvc.perform(delete(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void getMyProfile_returns200WithNickname_andPassesPrincipalUserId() throws Exception {
        when(userService.getProfile("v1", USER_ID))
                .thenReturn(User.of(Provider.KAKAO, "sub-123", null, "라이머"));

        mockMvc.perform(get(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andExpect(jsonPath("$.body.nickname").value("라이머"))
                .andExpect(header().exists("Transaction-Id"));

        // userId는 클라 입력이 아니라 인증 principal이고, applicationVersion과 함께 서비스로 전달된다.
        verify(userService).getProfile("v1", USER_ID);
    }

    @Test
    void getMyProfile_nullNickname_keepsExplicitNullKey() throws Exception {
        when(userService.getProfile("v1", USER_ID))
                .thenReturn(User.of(Provider.GOOGLE, "sub-123", "e@x.com", null));

        MvcResult result = mockMvc.perform(get(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0))
                .andReturn();

        // nickname 없음은 key 생략이 아니라 명시적 JSON null이다(NON_NULL 금지 계약).
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("body");
        assertThat(body.has("nickname")).isTrue();
        assertThat(body.get("nickname").isNull()).isTrue();
    }

    @Test
    void getMyProfile_missingUserRow_convergesToSame401() throws Exception {
        // 유효 토큰이라도 회원 행이 없으면 무토큰과 같은 401 -2001로 수렴한다(존재 비노출).
        when(userService.getProfile("v1", USER_ID))
                .thenThrow(new BusinessException(ExceptionType.API_AUTHENTICATION_REQUIRED));

        mockMvc.perform(get(PATH).with(authenticatedUser(USER_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist());
    }
}
