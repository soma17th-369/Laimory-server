package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** socket peer가 internal proxy가 아니면 forwarded-for를 무시하는 실제 Tomcat 회귀 테스트. */
@SpringBootTest(
        classes = RemoteIpValveUntrustedProxyTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.forward-headers-strategy=native",
                "server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.255"
        }
)
class RemoteIpValveUntrustedProxyTest {

    private static final String SPOOFED_IP = "198.51.100.9";
    private static final String SECOND_SPOOFED_IP = "203.0.113.7";

    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

    @LocalServerPort
    private int port;

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
    void untrustedSocketPeer_doesNotOverrideRemoteAddressFromForwardedFor() throws Exception {
        byte[] rawResponse;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = String.join("\r\n",
                    "GET /probe HTTP/1.1",
                    "Host: localhost",
                    "X-Forwarded-For: " + SPOOFED_IP + ", " + SECOND_SPOOFED_IP,
                    "Connection: close",
                    "",
                    "");
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            rawResponse = socket.getInputStream().readAllBytes();
        }

        String response = new String(rawResponse, StandardCharsets.ISO_8859_1);
        String transactionId = response.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT)
                        .startsWith(TransactionIds.HEADER_NAME.toLowerCase(Locale.ROOT) + ":"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .findFirst()
                .orElseThrow();
        ILoggingEvent event = accessLog.list.stream()
                .filter(candidate -> transactionId.equals(
                        candidate.getMDCPropertyMap().get(TransactionIds.MDC_KEY)))
                .findFirst()
                .orElseThrow();
        JsonNode log = encoded(event);

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(log.path("clientIp").asText())
                .isEqualTo("127.0.0.1")
                .isNotEqualTo(SPOOFED_IP)
                .isNotEqualTo(SECOND_SPOOFED_IP);
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
            SecurityAutoConfiguration.class
    }, excludeName = "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration")
    @Import({TransactionIdFilter.class, ProbeController.class})
    static class TestApplication {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
