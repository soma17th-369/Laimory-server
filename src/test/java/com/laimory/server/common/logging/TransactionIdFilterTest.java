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

/** TransactionIdFilter 단위 검증(발급/재사용/재발급, 응답 헤더, access 로그 레벨, MDC 정리). 인프라 0. */
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

    @Test
    void withoutHeader_issuesNewV7_andSetsResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String tx = response.getHeader(TransactionIds.HEADER_NAME);
        assertThat(tx).isNotNull();
        assertThat(UUID.fromString(tx).version()).isEqualTo(7);
    }

    @Test
    void withValidV7Header_reusesIt() throws Exception {
        String incoming = TransactionIds.newId();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.addHeader(TransactionIds.HEADER_NAME, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TransactionIds.HEADER_NAME)).isEqualTo(incoming);
    }

    @Test
    void withNonV7Header_issuesNewOne() throws Exception {
        String v4 = UUID.randomUUID().toString(); // version 4 — 재사용 금지 대상
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.addHeader(TransactionIds.HEADER_NAME, v4);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String tx = response.getHeader(TransactionIds.HEADER_NAME);
        assertThat(tx).isNotEqualTo(v4);
        assertThat(UUID.fromString(tx).version()).isEqualTo(7);
    }

    @Test
    void withGarbageHeader_issuesNewOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        request.addHeader(TransactionIds.HEADER_NAME, "not-a-uuid-with-injection-attempt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String tx = response.getHeader(TransactionIds.HEADER_NAME);
        assertThat(UUID.fromString(tx).version()).isEqualTo(7);
    }

    @Test
    void mdcIsPopulatedDuringChain_andClearedAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) {
                seenInChain[0] = TransactionIds.current();
            }
        }));

        assertThat(seenInChain[0]).isEqualTo(response.getHeader(TransactionIds.HEADER_NAME));
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
        assertThat(response.getHeader(TransactionIds.HEADER_NAME)).isNotNull(); // 예외여도 헤더는 이미 세팅됨
    }
}
