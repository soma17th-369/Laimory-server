package com.laimory.server.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.service.AuthTokenService;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.TransactionIds;
import com.laimory.server.config.SecurityConfig;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Auth 컨트롤러 슬라이스 테스트(MockMvc). 토큰/refresh/logout 성공 envelope와 ERROR_2002/2003 → 401 매핑을 고정한다. 인프라 0. */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    private static final String TOKEN = "/api/v1/auth/token";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";

    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthTokenService authTokenService;

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

    @Test
    void issueTokens_returns200WithTokenPair_andMasksTokensInAccessLog() throws Exception {
        when(authTokenService.issueTokens(any(), eq("code-1"), eq("verifier-1")))
                .thenReturn(new TokenResponse("access-abc", "refresh-def"));

        MvcResult result = mockMvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appCode\":\"code-1\",\"appVerifier\":\"verifier-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.body.accessToken").value("access-abc"))
                .andExpect(jsonPath("$.body.refreshToken").value("refresh-def"))
                .andReturn();

        JsonNode accessEvent = encoded(findAccessEvent(result));
        String loggedResponseBody = accessEvent.path("responseBody").asText();
        assertThat(loggedResponseBody).doesNotContain("access-abc", "refresh-def");
        JsonNode loggedResponse = objectMapper.readTree(loggedResponseBody);
        assertThat(loggedResponse.at("/body/accessToken").asText()).isEqualTo("***");
        assertThat(loggedResponse.at("/body/refreshToken").asText()).isEqualTo("***");
    }

    @Test
    void issueTokens_businessError2002_returns401Envelope() throws Exception {
        when(authTokenService.issueTokens(any(), any(), any()))
                .thenThrow(new BusinessException(ExceptionType.APP_CODE_INVALID));

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
                .thenThrow(new BusinessException(ExceptionType.REFRESH_TOKEN_INVALID));

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
