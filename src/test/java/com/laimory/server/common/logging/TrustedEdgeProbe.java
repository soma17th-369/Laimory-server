package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;

/**
 * trusted-edge 통합 테스트 공용 harness. 엣지별 신뢰 조건은 context 설정(peer 대역)이 결정하므로
 * loopback nginx 엣지와 ALB 엣지가 서로 다른 test class(=context)를 갖고, 요청·로그 관찰 코드는 여기 둔다.
 */
final class TrustedEdgeProbe {

    static final String EXTERNAL_HOST = "external.example";
    static final String ACCESS_LOGGER = "http.access";

    private TrustedEdgeProbe() {
    }

    /** OAuth 시작 요청을 raw HTTP로 보낸다 — 실제 Tomcat의 header 파싱을 그대로 통과시키기 위해서다. */
    static RawHttpResponse oauthRequest(int port, String... headers) throws IOException {
        String challenge = "A".repeat(43);
        List<String> lines = new ArrayList<>();
        lines.add("GET /oauth2/authorization/google?app_challenge=" + challenge + " HTTP/1.1");
        lines.add("Host: " + EXTERNAL_HOST);
        lines.addAll(List.of(headers));
        lines.add("Connection: close");
        lines.add("");
        lines.add("");
        return request(port, String.join("\r\n", lines));
    }

    /** 신뢰 엣지의 X-Forwarded-Proto가 OAuth redirect_uri와 Secure cookie까지 반영되는지 고정한다. */
    static void assertOauthHttpsContract(RawHttpResponse response) {
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.firstHeader(TransactionIds.HEADER_NAME)).isNotBlank();
        assertThat(queryParameter(response.firstHeader("Location"), "redirect_uri"))
                .isEqualTo("https://" + EXTERNAL_HOST + "/login/oauth2/code/google");
        assertThat(response.headers("Set-Cookie"))
                .anyMatch(cookie -> cookie.toLowerCase(Locale.ROOT).contains("secure"));
    }

    static ListAppender<ILoggingEvent> attachAccessLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ACCESS_LOGGER)).addAppender(appender);
        return appender;
    }

    static void detachAccessLogAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ACCESS_LOGGER)).detachAppender(appender);
    }

    static JsonNode accessLog(ListAppender<ILoggingEvent> appender, String transactionId, ObjectMapper objectMapper)
            throws IOException {
        ILoggingEvent event = appender.list.stream()
                .filter(logged -> transactionId.equals(logged.getMDCPropertyMap().get(TransactionIds.MDC_KEY)))
                .findFirst()
                .orElseThrow();

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            return objectMapper.readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }

    private static RawHttpResponse request(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return RawHttpResponse.parse(socket.getInputStream().readAllBytes());
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

    record RawHttpResponse(int status, Map<String, List<String>> headers) {

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
