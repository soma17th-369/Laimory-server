package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.ExceptionType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * TransactionIdFilter 단위 검증(요청마다 새 v7 발급, 응답 헤더 노출, access 로그 레벨, MDC 정리). 인프라 0.
 * tx의 클라이언트 노출은 응답 헤더 {@code Transaction-Id}가 담당한다 — 발급·헤더 계약(fresh v7,
 * MDC 일치, 클라 제공 값 무시)을 여기서 고정한다.
 */
class TransactionIdFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionIdFilter filter = new TransactionIdFilter(objectMapper);
    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

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

    /** 필터를 실행하고 체인 안에서 관측한 MDC의 tx를 돌려준다(응답 헤더는 호출부가 response로 단언). */
    private String runFilter(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        String[] seen = new String[1];
        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                seen[0] = MDC.get(TransactionIds.MDC_KEY);
            }
        }));
        return seen[0];
    }

    @Test
    void issuesFreshV7PerRequest() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        runFilter(new MockHttpServletRequest("GET", "/api/v1/intro"), first);
        runFilter(new MockHttpServletRequest("GET", "/api/v1/intro"), second);

        String firstId = first.getHeader("Transaction-Id");
        String secondId = second.getHeader("Transaction-Id");
        assertThat(UUID.fromString(firstId).version()).isEqualTo(7);
        assertThat(UUID.fromString(secondId).version()).isEqualTo(7);
        assertThat(firstId).isNotEqualTo(secondId); // 요청마다 새로 발급
    }

    @Test
    void responseHeaderMatchesMdcTransactionId() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String mdcObserved = runFilter(new MockHttpServletRequest("GET", "/api/v1/intro"), response);

        assertThat(response.getHeader("Transaction-Id")).isEqualTo(mdcObserved); // 헤더 값 == 로그의 tx
    }

    @Test
    void ignoresClientProvidedTransactionIdHeader() throws Exception {
        // 재사용 계약 없음: 클라가 같은 이름의 헤더를 보내도 무시하고 항상 서버가 발급한다.
        String clientSent = TransactionIds.newId();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.addHeader("Transaction-Id", clientSent);
        MockHttpServletResponse response = new MockHttpServletResponse();

        runFilter(request, response);

        String issued = response.getHeader("Transaction-Id");
        assertThat(issued).isNotEqualTo(clientSent); // 에코 아님
        assertThat(UUID.fromString(issued).version()).isEqualTo(7);
    }

    @Test
    void mdcIsPopulatedDuringChain_andClearedAfter() throws Exception {
        String observed = runFilter(new MockHttpServletRequest("GET", "/api/v1/intro"), new MockHttpServletResponse());

        assertThat(observed).isNotNull(); // 체인 실행 중엔 존재
        assertThat(MDC.get(TransactionIds.MDC_KEY)).isNull(); // finally에서 remove — 스레드 재사용 누수 방지
    }

    @Test
    void completionLog_isInfoForOkRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(accessLog.list.get(0).getFormattedMessage()).isEqualTo("http_request_completed");
        JsonNode json = encoded(accessLog.list.get(0));
        assertThat(json.get("event").asText()).isEqualTo("http_request_completed");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/intro");
    }

    @Test
    void completionLog_isSkippedForExcludedPath_butTransactionIdStillIssued() throws Exception {
        // 제외의 영향 반경은 완료 로그 한 줄뿐 — tx 발급·헤더는 유지된다(제외 경로의 앱 로그에도 tx가 붙도록).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/favicon.ico");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(accessLog.list).isEmpty();
        assertThat(response.getHeader("Transaction-Id")).isNotNull();
    }

    @Test
    void completionLog_isSkippedForHealthCheckPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(accessLog.list).isEmpty();
    }

    @Test
    void completionLog_excludedPathIsStillLoggedOnError() throws Exception {
        // 제외는 정상 완료에만 적용 — 제외 경로의 장애가 로그에서 사라지면 안 된다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");
        request.setAttribute(RequestLogAttributes.EXCEPTION_TYPE, ExceptionType.UNEXPECTED_ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    void completionLog_levelComesFromExceptionTypeAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/s/api/v1/timeline/callback");
        request.setAttribute(RequestLogAttributes.EXCEPTION_TYPE, ExceptionType.CALLBACK_TOKEN_ALREADY_USED);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.WARN); // 401이라서가 아니라 타입 레벨이 WARN이라서
        JsonNode json = encoded(accessLog.list.get(0));
        assertThat(json.get("errorCode").asText()).isEqualTo("ERROR_1012");
        assertThat(json.get("exceptionType").asText()).isEqualTo("CALLBACK_TOKEN_ALREADY_USED");
    }

    @Test
    void completionLog_statusAloneDoesNotChangeLevel() throws Exception {
        // 레벨의 SSOT는 ExceptionType — attribute 없는 4xx(봇 스캔 등 예외 경로 밖 응답)는 INFO로 남는다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void completionLog_jsonEncoderExpandsRecordToTopLevelFields() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.setAttribute(RequestLogAttributes.EXCEPTION_TYPE, ExceptionType.GEOCODING_PERMANENT_FAILURE);
        request.setAttribute(RequestLogAttributes.ERROR_DETAIL, "MapPlaceLookupException");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(502);

        filter.doFilter(request, response, new MockFilterChain());

        JsonNode json = encoded(accessLog.list.get(0));

        // marker의 record 프로퍼티가 top-level field로, 숫자는 숫자 타입 그대로 전개된다
        assertThat(json.get("message").asText()).isEqualTo("http_request_completed");
        assertThat(json.get("event").asText()).isEqualTo("http_request_completed");
        assertThat(json.get("level").asText()).isEqualTo("ERROR"); // 타입 레벨
        assertThat(json.get("status").isInt()).isTrue();
        assertThat(json.get("status").asInt()).isEqualTo(502);
        assertThat(json.get("latencyMs").isNumber()).isTrue();
        assertThat(json.get("errorCode").asText()).isEqualTo("ERROR_1015");
        assertThat(json.get("exceptionType").asText()).isEqualTo("GEOCODING_PERMANENT_FAILURE");
        assertThat(json.get("errorDetail").asText()).isEqualTo("MapPlaceLookupException");
        assertThat(json.get("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(json.get("requestBody").isNull()).isTrue();
        assertThat(json.get("responseBody").isNull()).isTrue();
    }

    @Test
    void completionLog_capturesAndMasksJsonBodies_withoutChangingClientResponse() throws Exception {
        String requestSecret = "RAW_REFRESH_TOKEN_152_NEVER_LOG";
        String responseSecret = "RAW_ACCESS_TOKEN_152_NEVER_LOG";
        String requestJson = "{\"safe\":\"request\",\"refreshToken\":\"" + requestSecret + "\"}";
        String responseJson = "{\"safe\":\"response\",\"accessToken\":\"" + responseSecret + "\"}";
        MockHttpServletRequest request = jsonRequest("POST", "/api/v1/test", requestJson);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
                req.getInputStream().readAllBytes();
                res.setContentType("application/json");
                res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                res.getWriter().write(responseJson);
            }
        }));

        assertThat(response.getContentAsString()).isEqualTo(responseJson);
        ILoggingEvent event = accessLog.list.get(0);
        JsonNode json = encoded(event);
        assertThat(objectMapper.readTree(json.get("requestBody").asText()).get("refreshToken").asText())
                .isEqualTo("***");
        assertThat(objectMapper.readTree(json.get("responseBody").asText()).get("accessToken").asText())
                .isEqualTo("***");
        assertThat(event.getFormattedMessage()).doesNotContain(requestSecret, responseSecret);
        assertThat(event.getMarkerList().toString()).doesNotContain(requestSecret, responseSecret);
        assertThat(new String(encode(event), StandardCharsets.UTF_8)).doesNotContain(requestSecret, responseSecret);
    }

    @Test
    void requestBody_isNullWhenChainDoesNotReadRequestStream() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/v1/test", "{\"safe\":true}");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(encoded(accessLog.list.get(0)).get("requestBody").isNull()).isTrue();
    }

    @Test
    void responseLogPreviewIsTruncated_butClientReceivesFullBody() throws Exception {
        String responseJson = "{\"safe\":\"" + "가".repeat(9000) + "\"}";
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/test"), response,
                new MockFilterChain(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
                        res.setContentType("application/json");
                        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        res.getWriter().write(responseJson);
                    }
                }));

        assertThat(response.getContentAsString()).isEqualTo(responseJson);
        assertThat(encoded(accessLog.list.get(0)).get("responseBody").asText())
                .hasSize(8192)
                .endsWith("…");
    }

    @Test
    void encodedPollingAndWorstCasePreviewStayWellBelowDockerSingleLineLimit() throws Exception {
        String processing = """
                {"header":{"code":"COMMON_0000","message":"성공"},
                 "body":{"status":"PROCESSING","result":null,"error":null}}
                """;
        String success = representativeSuccessPollingBody();
        String escapeHeavy = objectMapper.writeValueAsString(Map.of(
                "preview", "가\"\\\t".repeat(2500)));

        int processingBytes = encodedSizeForJsonResponse(processing);
        int successBytes = encodedSizeForJsonResponse(success);
        int escapeHeavyBytes = encodedSizeForJsonResponse(escapeHeavy);

        // 2026-07-16 실측: PROCESSING 652B, 12 events × 4 items SUCCESS 10,387B,
        // escape-heavy 8,192자 preview 16,899B(서비스/환경 공통 field 포함).
        assertThat(processingBytes).isLessThan(2 * 1024);
        assertThat(successBytes).isLessThan(32 * 1024);
        assertThat(escapeHeavyBytes).isLessThan(64 * 1024);
        assertThat(30 * 1024 * 1024 / successBytes).isGreaterThan(2500);
    }

    @Test
    void completionLog_recordsRemoteAddressAsClientIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.setRemoteAddr("203.0.113.7");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(encoded(accessLog.list.get(0)).get("clientIp").asText()).isEqualTo("203.0.113.7");
    }

    @Test
    void unexpectedMaskerFailure_doesNotFailSuccessfulResponse() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.readTree(anyString())).thenThrow(new IllegalStateException("masker failure"));
        TransactionIdFilter failingFilter = new TransactionIdFilter(failingMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failingFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/test"), response,
                new MockFilterChain(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
                        res.setContentType("application/json");
                        res.getWriter().write("{\"safe\":true}");
                    }
                }));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"safe\":true}");
        assertThat(accessLog.list).isEmpty();
    }

    @Test
    void maskerFailureNeverReplacesOriginalChainException() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.readTree(anyString())).thenThrow(new IllegalStateException("masker failure"));
        TransactionIdFilter failingFilter = new TransactionIdFilter(failingMapper);
        MockHttpServletRequest request = jsonRequest("POST", "/api/v1/test", "{\"safe\":true}");
        ServletException original = new ServletException("original");
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res)
                    throws ServletException, java.io.IOException {
                req.getInputStream().readAllBytes();
                throw original;
            }
        });

        Throwable thrown = catchThrowable(() -> failingFilter.doFilter(request, new MockHttpServletResponse(), chain));

        assertThat(thrown).isSameAs(original);
    }

    @Test
    void uncaughtException_isLoggedAs500Error_thenRethrown_andMdcCleared() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain throwingChain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res)
                    throws ServletException, java.io.IOException {
                res.setContentType("application/json");
                res.getWriter().write("{\"partial\":\"DISCARDED_RESPONSE_152\"");
                throw new ServletException("boom");
            }
        });

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(ServletException.class);

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        JsonNode json;
        try {
            json = encoded(accessLog.list.get(0));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(json.get("status").asInt()).isEqualTo(500);
        assertThat(json.get("responseBody").asText())
                .isEqualTo(AccessLogBodyMasker.UNHANDLED_EXCEPTION_BODY)
                .doesNotContain("DISCARDED_RESPONSE_152");
        assertThat(response.getHeader("Transaction-Id")).isNotNull(); // 예외 경로에도 헤더는 chain 진입 전 설정돼 있음
        assertThat(MDC.get(TransactionIds.MDC_KEY)).isNull();
    }

    private JsonNode encoded(ILoggingEvent event) throws Exception {
        return objectMapper.readTree(encode(event));
    }

    private int encodedSizeForJsonResponse(String responseBody) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/task-152"), response,
                new MockFilterChain(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest req, HttpServletResponse res) throws java.io.IOException {
                        res.setContentType("application/json");
                        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        res.getWriter().write(responseBody);
                    }
                }));
        return encode(accessLog.list.get(accessLog.list.size() - 1)).length;
    }

    private String representativeSuccessPollingBody() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int eventIndex = 0; eventIndex < 12; eventIndex++) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int itemIndex = 0; itemIndex < 4; itemIndex++) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("filename", "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
                payload.put("clientPhotoUri", "content://media/external/images/media/152");
                payload.put("latitude", 37.5665);
                payload.put("longitude", 126.9780);
                payload.put("address", "서울특별시 중구 세종대로");
                payload.put("photoUrl", "https://cdn.example/photos/sample.jpg");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("timelineItemId", eventIndex * 10L + itemIndex);
                item.put("itemType", "PHOTO");
                item.put("rawId", "raw-" + eventIndex + "-" + itemIndex);
                item.put("startAt", "2026-07-15T09:00:00");
                item.put("endAt", null);
                item.put("payload", payload);
                items.add(item);
            }
            events.add(Map.of(
                    "timelineEventId", (long) eventIndex,
                    "startAt", "2026-07-15T09:00:00",
                    "endAt", "2026-07-15T10:00:00",
                    "title", "대표 일정 " + eventIndex,
                    "subtitle", "하루치 타임라인 크기 측정",
                    "memo", "응답 body 로그의 bounded preview를 검증한다.",
                    "items", items));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("result", Map.of(
                "recordDate", "2026-07-15",
                "emotionType", "HAPPY",
                "events", events));
        body.put("error", null);
        return objectMapper.writeValueAsString(Map.of(
                "header", Map.of("code", "COMMON_0000", "message", "성공"),
                "body", body));
    }

    private static byte[] encode(ILoggingEvent event) {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.setCustomFields("{\"service\":\"laimory\",\"environment\":\"dev\"}");
        encoder.start();
        try {
            return encoder.encode(event);
        } finally {
            encoder.stop();
        }
    }

    private static MockHttpServletRequest jsonRequest(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
