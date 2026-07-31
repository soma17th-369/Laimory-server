package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.controller.AuthHandoffPageController;
import com.laimory.server.auth.service.SocialLoginService;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.logging.TransactionIds;
import com.laimory.server.testsupport.AuthTestSupport;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 두 필터체인의 계약 고정: 로그인 시작의 PKCE 강제·app_challenge 필수(400 envelope), API 체인의
 * /a/api 인증 강제(무토큰/무효 토큰 → 401 ERROR_2001, 유효 토큰 → 통과), 공개 경로 무인증 유지,
 * 핸드오프 안내 페이지의 code 비표시, App Link 검증 파일(assetlinks.json)의 무인증 JSON 제공.
 */
@WebMvcTest(controllers = AuthHandoffPageController.class)
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class, OAuth2LoginSecurityConfig.class})
class SecurityConfigTest {

    private static final String VALID_CHALLENGE = AuthTokens.challenge("any-verifier");

    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.laimory.server.auth.token.JwtTokens jwtTokens;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SocialLoginService socialLoginService;

    @BeforeEach
    void attachAccessLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("http.access");
        accessLog.start();
        logger.addAppender(accessLog);
    }

    @AfterEach
    void detachAccessLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("http.access");
        logger.detachAppender(accessLog);
    }

    @TestConfiguration
    static class DummyClientRegistrations {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test-client")
                    .clientSecret("test-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/google")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                    .userNameAttributeName("sub")
                    .clientName("google")
                    .build());
        }
    }

    @Test
    void authorizationStart_forcesPkceAndStateAndNonce() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google")
                        .queryParam("app_challenge", VALID_CHALLENGE))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).contains("code_challenge=");
        assertThat(location).contains("code_challenge_method=S256");
        assertThat(location).contains("state=");
        assertThat(location).contains("nonce=");
    }

    @Test
    void authorizationStart_withoutAppChallenge_returns400Envelope() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400))
                .andExpect(header().exists("Transaction-Id")) // 필터단 직접 400에도 tx 헤더(TransactionIdFilter가 Security 체인보다 앞)
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void authorizationStart_withMalformedAppChallenge_returns400Envelope() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google").queryParam("app_challenge", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(-400));
    }

    @Test
    void handoffLanding_rendersGuideWithoutEchoingCode() throws Exception {
        mockMvc.perform(get("/auth/app").queryParam("code", "SECRET123"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(content().string(containsString("로그인")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("SECRET123"))));
    }

    @Test
    void assetLinks_servedWithoutAuthAsExactDebugAppRelation() throws Exception {
        // Android verifier가 redirect·인증 없이 읽어야 debug 앱 App Link 도메인 검증이 성립한다.
        mockMvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
                .andExpect(jsonPath("$[0].target.package_name").value("com.soma369.laimory.debug"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value(
                        "95:7C:55:EE:29:A5:D4:71:73:47:FB:6A:D3:60:06:9A:4D:06:82:9F:"
                                + "B5:48:D3:E7:4C:23:35:38:90:53:95:8B"));
    }

    @Test
    void authenticatedPrefix_withoutToken_returns401Envelope() throws Exception {
        // /a/api 인증 강제: 무토큰 요청은 컨트롤러 유무와 무관하게 Security 단계에서 401 ERROR_2001로 거절된다.
        mockMvc.perform(get("/a/api/v1/timeline/drafts/whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001))
                .andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                // Security 단계 401에도 tx 헤더(TransactionIdFilter가 Security 체인보다 앞).
                .andExpect(header().exists("Transaction-Id"));
    }

    @Test
    void authenticatedPrefix_withInvalidToken_returns401Envelope() throws Exception {
        mockMvc.perform(get("/a/api/v1/timeline/drafts/whatever")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
    }

    @Test
    void authenticatedPrefix_withValidToken_passesSecurityToHandler404() throws Exception {
        // 유효 토큰은 security를 통과한다 — 이 슬라이스엔 timeline 컨트롤러가 없어 핸들러 404 envelope에 도달.
        String token = jwtTokens.issueAccessToken(42L);

        MvcResult result = mockMvc.perform(get("/a/api/v1/timeline/drafts/whatever")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404))
                .andReturn();

        JsonNode accessEvent = encoded(findAccessEvent(result));
        assertThat(accessEvent.get("userId").isIntegralNumber()).isTrue();
        assertThat(accessEvent.get("userId").asLong()).isEqualTo(42L);
    }

    @Test
    void publicPrefixes_remainAccessibleWithoutToken() throws Exception {
        // 공개(/api)·서버간(/s/api) 경로는 Bearer 없음만으로 거절되지 않는다 — 미매핑이라 404 envelope(401 아님).
        mockMvc.perform(get("/api/v1/whatever"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
        mockMvc.perform(get("/s/api/v1/whatever"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    @Test
    void similarStringPrefix_isNotProtected() throws Exception {
        // 문자열 prefix가 아니라 경로 세그먼트 매칭 — /a/apiary는 보호 대상이 아니다(404, 401 아님).
        mockMvc.perform(get("/a/apiary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.code").value(-404));
    }

    private ILoggingEvent findAccessEvent(MvcResult result) {
        String transactionId = result.getResponse().getHeader(TransactionIds.HEADER_NAME);
        assertThat(transactionId).isNotBlank();
        return accessLog.list.stream()
                .filter(event -> transactionId.equals(
                        event.getMDCPropertyMap().get(TransactionIds.MDC_KEY)))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode encoded(ILoggingEvent event) throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            return objectMapper.readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }
}
