package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.RequestLogAttributes;
import com.laimory.server.common.logging.TransactionIdFilter;
import jakarta.servlet.http.HttpServlet;
import java.util.Locale;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * app_challenge 거절 경로의 access 로그 합류 계약. 이 경로는 전역 핸들러를 거치지 않고 필터가
 * 직접 400 envelope을 쓰는 유일한 경로라, attribute 세팅과 로그 레벨(ExceptionType이 SSOT)을
 * {@link TransactionIdFilter}와의 체인으로 통합 검증한다. 인프라 0.
 */
class AppChallengeFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionIdFilter transactionIdFilter = new TransactionIdFilter(objectMapper);
    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

    private AppChallengeFilter appChallengeFilter;

    @BeforeEach
    void setUp() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("ERROR_0400", Locale.KOREAN, "잘못된 요청입니다.");
        appChallengeFilter = new AppChallengeFilter(messageSource, objectMapper);

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
    void missingChallenge_writes400Envelope_andJoinsAccessLogAsInfo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        transactionIdFilter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
        }, appChallengeFilter));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode clientBody = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(clientBody.at("/header/code").asText()).isEqualTo("ERROR_0400");
        assertThat(clientBody.at("/header/message").asText()).isEqualTo("잘못된 요청입니다.");
        assertThat(clientBody.get("body").isNull()).isTrue();
        assertThat(request.getAttribute(RequestLogAttributes.EXCEPTION_TYPE))
                .isEqualTo(ExceptionType.APP_CHALLENGE_REJECTED);
        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.INFO); // 400이어도 레벨은 타입(INFO)이 정한다
        JsonNode log = encoded(accessLog.list.get(0));
        assertThat(log.get("errorCode").asText()).isEqualTo("ERROR_0400");
        assertThat(log.get("exceptionType").asText()).isEqualTo("APP_CHALLENGE_REJECTED");
        assertThat(objectMapper.readTree(log.get("responseBody").asText())).isEqualTo(clientBody);
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
