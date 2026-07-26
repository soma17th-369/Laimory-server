package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.ServerApplication;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.logstash.logback.encoder.LogstashEncoder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** 실제 Tomcat에서 trusted-edge client IP와 OAuth HTTPS redirect/session cookie 계약을 검증한다. */
@Tag("integration")
@ActiveProfiles("docker")
@SpringBootTest(
        classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class TrustedEdgeIntegrationTest {

    private static final String SPOOFED_XFF_IP = "198.51.100.9";
    private static final String EDGE_CLIENT_IP = "203.0.113.7";
    private static final String EXTERNAL_HOST = "external.example";

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
    void trustedEdge_usesCustomIpAndExternalHttpsForOauthRedirect() throws Exception {
        RawHttpResponse response = oauthRequest(
                "Laimory-Client-IP: " + EDGE_CLIENT_IP,
                "X-Forwarded-For: " + SPOOFED_XFF_IP,
                "X-Forwarded-Proto: https");

        assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode log = encoded(findAccessEvent(response.firstHeader(TransactionIds.HEADER_NAME)));
            assertThat(log.path("event").asText()).isEqualTo("http_request_completed");
            assertThat(log.path("clientIp").asText())
                    .isEqualTo(EDGE_CLIENT_IP)
                    .isNotEqualTo(SPOOFED_XFF_IP);
        });
    }

    @Test
    void missingCustomIp_ignoresXffButStillPreservesTrustedHttps() throws Exception {
        RawHttpResponse response = oauthRequest(
                "X-Forwarded-For: " + SPOOFED_XFF_IP,
                "X-Forwarded-Proto: https");

        assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode log = encoded(findAccessEvent(response.firstHeader(TransactionIds.HEADER_NAME)));
            assertThat(log.path("clientIp").asText())
                    .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER)
                    .isNotEqualTo(SPOOFED_XFF_IP);
        });
    }

    @Test
    void repeatedCustomIp_fallsBackToSocketPeer() throws Exception {
        RawHttpResponse response = oauthRequest(
                "Laimory-Client-IP: " + EDGE_CLIENT_IP,
                "Laimory-Client-IP: " + EDGE_CLIENT_IP,
                "X-Forwarded-Proto: https");

        assertOauthHttpsContract(response);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(encoded(findAccessEvent(response.firstHeader(TransactionIds.HEADER_NAME)))
                        .path("clientIp").asText())
                        .isEqualTo(TrustedEdgeRequestFilter.TRUSTED_SOCKET_PEER));
    }

    private RawHttpResponse oauthRequest(String... headers) throws IOException {
        String challenge = "A".repeat(43);
        List<String> lines = new ArrayList<>();
        lines.add("GET /oauth2/authorization/google?app_challenge=" + challenge + " HTTP/1.1");
        lines.add("Host: " + EXTERNAL_HOST);
        lines.addAll(List.of(headers));
        lines.add("Connection: close");
        lines.add("");
        lines.add("");
        return request(String.join("\r\n", lines));
    }

    private void assertOauthHttpsContract(RawHttpResponse response) {
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.firstHeader(TransactionIds.HEADER_NAME)).isNotBlank();
        assertThat(queryParameter(response.firstHeader("Location"), "redirect_uri"))
                .isEqualTo("https://" + EXTERNAL_HOST + "/login/oauth2/code/google");
        assertThat(response.headers("Set-Cookie"))
                .anyMatch(cookie -> cookie.toLowerCase(Locale.ROOT).contains("secure"));
    }

    private RawHttpResponse request(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return RawHttpResponse.parse(socket.getInputStream().readAllBytes());
        }
    }

    private ILoggingEvent findAccessEvent(String transactionId) {
        return accessLog.list.stream()
                .filter(event -> transactionId.equals(event.getMDCPropertyMap().get(TransactionIds.MDC_KEY)))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode encoded(ILoggingEvent event) throws IOException {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            return objectMapper.readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }

    private static String queryParameter(String location, String name) {
        String rawQuery = URI.create(location).getRawQuery();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("missing query parameter: " + name);
    }

    private record RawHttpResponse(int status, Map<String, List<String>> headers) {

        static RawHttpResponse parse(byte[] bytes) {
            String head = new String(bytes, StandardCharsets.ISO_8859_1).split("\\r\\n\\r\\n", 2)[0];
            String[] lines = head.split("\\r\\n");
            int status = Integer.parseInt(lines[0].split(" ", 3)[1]);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                String[] header = lines[index].split(":", 2);
                headers.computeIfAbsent(header[0].toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(header[1].trim());
            }
            return new RawHttpResponse(status, headers);
        }

        String firstHeader(String name) {
            return headers(name).stream().findFirst().orElse(null);
        }

        List<String> headers(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }
}
