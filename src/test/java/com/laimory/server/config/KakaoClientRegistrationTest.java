package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.auth.controller.AuthHandoffPageController;
import com.laimory.server.auth.service.SocialLoginService;
import com.laimory.server.auth.token.AuthTokens;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 실제 application.properties의 Kakao client 바인딩 회귀 고정(#167 완료 조건).
 *
 * <p>{@code SecurityConfigTest}는 직접 만든 dummy registration으로 오토컨피그를 back-off시키므로
 * 거기서는 실 property binding이 검증되지 않는다. 이 테스트는 registration 빈을 제공하지 않아
 * 오토컨피그가 application.properties를 바인딩하고, 그 결과인 authorization redirect의 scope를 고정한다.
 */
@WebMvcTest(controllers = AuthHandoffPageController.class)
@Import({SecurityConfig.class, OAuth2LoginSecurityConfig.class})
@TestPropertySource(properties = {
        // application.properties의 env placeholder만 채운다 — scope 등 나머지는 실 property가 그대로 바인딩된다.
        "GOOGLE_CLIENT_ID=test-google-id",
        "GOOGLE_CLIENT_SECRET=test-google-secret",
        "KAKAO_CLIENT_ID=test-kakao-id",
        "KAKAO_CLIENT_SECRET=test-kakao-secret"
})
class KakaoClientRegistrationTest {

    private static final String VALID_CHALLENGE = AuthTokens.challenge("any-verifier");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SocialLoginService socialLoginService;

    @Test
    void kakaoAuthorizationRedirect_scopeIsExactlyOpenidAndProfileNickname() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/kakao")
                        .queryParam("app_challenge", VALID_CHALLENGE))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize");
        String scope = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("scope");
        assertThat(URLDecoder.decode(scope, StandardCharsets.UTF_8)).isEqualTo("openid profile_nickname");
    }
}
