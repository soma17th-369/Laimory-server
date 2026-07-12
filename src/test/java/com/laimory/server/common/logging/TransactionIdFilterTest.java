package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private final TransactionIdFilter filter = new TransactionIdFilter();
    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();

    @BeforeEach
    void attachAccessLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("http.access");
        logger.setLevel(Level.DEBUG); // quiet(DEBUG) 강등 검증을 위해 수집 레벨을 낮춘다
        accessLog.start();
        logger.addAppender(accessLog);
    }

    @AfterEach
    void detachAccessLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("http.access");
        logger.detachAppender(accessLog);
        logger.setLevel(null);
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
        assertThat(accessLog.list.get(0).getFormattedMessage())
                .contains("event=http_request_completed")
                .contains("path=/api/v1/intro");
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
    void completionLog_isDebugForHealthCheckPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
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
        assertThat(accessLog.list.get(0).getFormattedMessage())
                .contains("errorCode=ERROR_1012")
                .contains("exceptionType=CALLBACK_TOKEN_ALREADY_USED");
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

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        JsonNode json = new ObjectMapper().readTree(encoder.encode(accessLog.list.get(0)));

        // record 프로퍼티가 escape된 문자열이 아니라 top-level 필드로, 숫자는 숫자 타입 그대로 전개된다
        assertThat(json.get("event").asText()).isEqualTo("http_request_completed");
        assertThat(json.get("level").asText()).isEqualTo("ERROR"); // 타입 레벨
        assertThat(json.get("status").isInt()).isTrue();
        assertThat(json.get("status").asInt()).isEqualTo(502);
        assertThat(json.get("latencyMs").isNumber()).isTrue();
        assertThat(json.get("errorCode").asText()).isEqualTo("ERROR_1015");
        assertThat(json.get("exceptionType").asText()).isEqualTo("GEOCODING_PERMANENT_FAILURE");
        assertThat(json.get("errorDetail").asText()).isEqualTo("MapPlaceLookupException");
    }

    @Test
    void uncaughtException_isLoggedAs500Error_thenRethrown_andMdcCleared() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain throwingChain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                throw new ServletException("boom");
            }
        });

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(ServletException.class);

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(accessLog.list.get(0).getFormattedMessage()).contains("status=500");
        assertThat(response.getHeader("Transaction-Id")).isNotNull(); // 예외 경로에도 헤더는 chain 진입 전 설정돼 있음
        assertThat(MDC.get(TransactionIds.MDC_KEY)).isNull();
    }
}
