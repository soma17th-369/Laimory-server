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
 * 실제 Tomcat에서 <b>ALB 엣지</b>의 client IP(X-Forwarded-For 최우측)와 OAuth HTTPS redirect 계약을
 * 검증한다. 테스트 소켓의 peer는 loopback이므로 신뢰 대역을 {@code 127.0.0.1/32}로 설정해 ALB ENI 자리를
 * 대신한다 — 필터가 설정된 CIDR을 loopback nginx 분기보다 먼저 평가하기 때문에 같은 소켓으로 ALB 경로를
 * 재현할 수 있다. 운영에서 두 대역은 서로소다.
 */
@Tag("integration")
@ActiveProfiles("docker")
@SpringBootTest(
        classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "app.edge.trusted-proxy-cidrs=127.0.0.1/32,::1/128"})
class TrustedEdgeProxyIntegrationTest {

    private static final String SPOOFED_IP = "1.2.3.4";
    private static final String ALB_OBSERVED_IP = "203.0.113.7";

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

    /** 클라이언트가 선행 주입한 XFF 값이 아니라 ALB가 오른쪽에 append한 값이 채택돼야 한다. */
    @Test
    void spoofedForwardedForPrefix_losesToRightmostValue() throws Exception {
        RawHttpResponse response = TrustedEdgeProbe.oauthRequest(port,
                "X-Forwarded-For: " + SPOOFED_IP + ", " + ALB_OBSERVED_IP,
                "X-Forwarded-Proto: https");

        TrustedEdgeProbe.assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode log = accessLog(response);
            assertThat(log.path("event").asText()).isEqualTo("http_request_completed");
            assertThat(log.path("clientIp").asText())
                    .isEqualTo(ALB_OBSERVED_IP)
                    .isNotEqualTo(SPOOFED_IP);
        });
    }

    /** 클라이언트가 별도 header line으로 주입해도 마지막 line(= ALB가 붙인 값)만 본다. */
    @Test
    void spoofedForwardedForHeaderLine_losesToLastLine() throws Exception {
        RawHttpResponse response = TrustedEdgeProbe.oauthRequest(port,
                "X-Forwarded-For: " + SPOOFED_IP,
                "X-Forwarded-For: " + ALB_OBSERVED_IP,
                "X-Forwarded-Proto: https");

        TrustedEdgeProbe.assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(accessLog(response).path("clientIp").asText())
                        .isEqualTo(ALB_OBSERVED_IP)
                        .isNotEqualTo(SPOOFED_IP));
    }

    /** ALB는 임의 이름의 custom header를 덮어쓰지 못하므로 이 엣지에서 Laimory-Client-IP는 신뢰하지 않는다. */
    @Test
    void customClientIpHeader_isIgnoredAndFallsBackToSocketPeer() throws Exception {
        RawHttpResponse response = TrustedEdgeProbe.oauthRequest(port,
                "Laimory-Client-IP: " + SPOOFED_IP,
                "X-Forwarded-Proto: https");

        TrustedEdgeProbe.assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(accessLog(response).path("clientIp").asText())
                        .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER)
                        .isNotEqualTo(SPOOFED_IP));
    }

    private JsonNode accessLog(RawHttpResponse response) throws Exception {
        return TrustedEdgeProbe.accessLog(accessLog, response.firstHeader(TransactionIds.HEADER_NAME), objectMapper);
    }
}
