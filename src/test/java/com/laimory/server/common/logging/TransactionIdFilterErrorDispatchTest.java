package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 실제 Tomcat error dispatch가 미커밋 부분 body를 최종 500 응답으로 교체할 수 있는지 검증한다. */
@SpringBootTest(
        classes = TransactionIdFilterErrorDispatchTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=-1"
)
class TransactionIdFilterErrorDispatchTest {

    private static final String DISCARDED_SENTINEL = "DISCARDED_RESPONSE_152";

    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
    void unhandledException_doesNotCommitOrLogDiscardedPartialBody() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/partial-response", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst(TransactionIds.HEADER_NAME)).isNotBlank();
        assertThat(response.getBody() == null || !response.getBody().contains(DISCARDED_SENTINEL)).isTrue();

        String transactionId = response.getHeaders().getFirst(TransactionIds.HEADER_NAME);
        ILoggingEvent event = accessLog.list.stream()
                .filter(candidate -> transactionId.equals(
                        candidate.getMDCPropertyMap().get(TransactionIds.MDC_KEY)))
                .findFirst()
                .orElseThrow();
        JsonNode log = encoded(event);

        assertThat(log.get("status").asInt()).isEqualTo(500);
        assertThat(log.get("responseBody").asText())
                .isEqualTo(AccessLogBodyMasker.UNHANDLED_EXCEPTION_BODY)
                .doesNotContain(DISCARDED_SENTINEL);
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

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            SessionAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class
    }, excludeName = "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration")
    @Import({TransactionIdFilter.class, PartialResponseController.class})
    static class TestApplication {
    }

    @RestController
    static class PartialResponseController {

        @GetMapping("/partial-response")
        void partialResponse(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.getWriter().write("{\"partial\":\"" + DISCARDED_SENTINEL + "\"");
            throw new IllegalStateException("test exception after partial response");
        }
    }
}
