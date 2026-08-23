package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.ServerApplication;
import com.laimory.server.common.logging.TrustedEdgeProbe.RawHttpResponse;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 Tomcat에서 <b>loopback nginx 엣지</b>의 client IP와 OAuth HTTPS redirect/session cookie 계약을
 * 검증한다(현행 dev 경로: client → nginx:443 → 127.0.0.1:8080). ALB 엣지는
 * {@link TrustedEdgeProxyIntegrationTest}가 담당한다.
 */
@Tag("integration")
@ActiveProfiles("docker")
@SpringBootTest(
        classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class TrustedEdgeIntegrationTest {

    private static final String SPOOFED_XFF_IP = "198.51.100.9";
    private static final String EDGE_CLIENT_IP = "203.0.113.7";

    private ListAppender<ILoggingEvent> accessLog;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void attachAccessLogAppender() {
        accessLog = TrustedEdgeProbe.attachAccessLogAppender();
    }

    @AfterEach
    void detachAccessLogAppender() {
        TrustedEdgeProbe.detachAccessLogAppender(accessLog);
    }

    @Test
    void trustedEdge_usesCustomIpAndExternalHttpsForOauthRedirect() throws Exception {
        RawHttpResponse response = TrustedEdgeProbe.oauthRequest(port,
                "Laimory-Client-IP: " + EDGE_CLIENT_IP,
                "X-Forwarded-For: " + SPOOFED_XFF_IP,
                "X-Forwarded-Proto: https");

        TrustedEdgeProbe.assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode log = accessLog(response);
            assertThat(log.path("event").asText()).isEqualTo("http_request_completed");
            assertThat(log.path("clientIp").asText())
                    .isEqualTo(EDGE_CLIENT_IP)
                    .isNotEqualTo(SPOOFED_XFF_IP);
        });
    }

    @Test
    void missingCustomIp_ignoresXffButStillPreservesTrustedHttps() throws Exception {
        RawHttpResponse response = TrustedEdgeProbe.oauthRequest(port,
                "X-Forwarded-For: " + SPOOFED_XFF_IP,
                "X-Forwarded-Proto: https");

        TrustedEdgeProbe.assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(accessLog(response).path("clientIp").asText())
                        .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER)
                        .isNotEqualTo(SPOOFED_XFF_IP));
    }

    @Test
    void repeatedCustomIp_fallsBackToSocketPeer() throws Exception {
        RawHttpResponse response = TrustedEdgeProbe.oauthRequest(port,
                "Laimory-Client-IP: " + EDGE_CLIENT_IP,
                "Laimory-Client-IP: " + EDGE_CLIENT_IP,
                "X-Forwarded-Proto: https");

        TrustedEdgeProbe.assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(accessLog(response).path("clientIp").asText())
                        .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER));
    }

    private JsonNode accessLog(RawHttpResponse response) throws Exception {
        return TrustedEdgeProbe.accessLog(accessLog, response.firstHeader(TransactionIds.HEADER_NAME), objectMapper);
    }
}
