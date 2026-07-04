package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * TransactionIdFilter 단위 검증(요청마다 새 v7 발급, access 로그 레벨, MDC 정리). 인프라 0.
 * tx의 클라이언트 노출은 envelope body가 담당하므로 HTTP 헤더 계약은 없다 — 여기서도 헤더를 단언하지 않는다.
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

    /** 체인 안에서 관측한 현재 tx를 돌려주는 헬퍼 — 응답 헤더가 없으므로 MDC로 관측한다. */
    private String observeTransactionId(MockHttpServletRequest request) throws Exception {
        String[] seen = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                seen[0] = TransactionIds.current();
            }
        }));
        return seen[0];
    }

    @Test
    void issuesFreshV7PerRequest() throws Exception {
        String first = observeTransactionId(new MockHttpServletRequest("GET", "/api/v1/intro"));
        String second = observeTransactionId(new MockHttpServletRequest("GET", "/api/v1/intro"));

        assertThat(UUID.fromString(first).version()).isEqualTo(7);
        assertThat(UUID.fromString(second).version()).isEqualTo(7);
        assertThat(first).isNotEqualTo(second); // 요청마다 새로 발급
    }

    @Test
    void ignoresClientProvidedTransactionIdHeader() throws Exception {
        // 재사용 계약 없음: 클라가 X-Transaction-Id를 보내도 무시하고 항상 서버가 발급한다.
        String clientSent = TransactionIds.newId();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.addHeader("X-Transaction-Id", clientSent);

        String observed = observeTransactionId(request);

        assertThat(observed).isNotEqualTo(clientSent);
        assertThat(UUID.fromString(observed).version()).isEqualTo(7);
    }

    @Test
    void mdcIsPopulatedDuringChain_andClearedAfter() throws Exception {
        String observed = observeTransactionId(new MockHttpServletRequest("GET", "/api/v1/intro"));

        assertThat(observed).isNotNull(); // 체인 실행 중엔 존재
        assertThat(TransactionIds.current()).isNull(); // finally에서 remove — 스레드 재사용 누수 방지
        assertThat(MDC.get(TransactionIds.MDC_KEY)).isNull();
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
    void completionLog_isDebugForHealthCheckPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }

    @Test
    void completionLog_isWarnFor4xx() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(accessLog.list.get(0).getLevel()).isEqualTo(Level.WARN);
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
        assertThat(MDC.get(TransactionIds.MDC_KEY)).isNull();
    }
}
