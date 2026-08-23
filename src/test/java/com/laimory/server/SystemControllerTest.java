package com.laimory.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * {@code /status} 응답 계약 검증(인프라 0). 503 본문에 예외 원문({@code error} 키)이 없고 진단은
 * 서비스 로그 정확히 한 줄(stacktrace 포함)이 담당한다(#331 — 공개 경로라 DB 계정·내부 주소 노출 금지).
 * 200 본문({@code status,db})은 blackbox probe가 보는 계약이라 불변임을 함께 고정한다.
 */
class SystemControllerTest {

    private static final String LEAKY_MESSAGE = "Access denied for user 'svc'@'10.0.2.152'";

    private final DataSource dataSource = mock(DataSource.class);
    private final SystemController controller = new SystemController(dataSource);
    private final ListAppender<ILoggingEvent> serviceLog = new ListAppender<>();

    @BeforeEach
    void attachServiceLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SystemController.class);
        serviceLog.start();
        logger.addAppender(serviceLog);
    }

    @AfterEach
    void detachServiceLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SystemController.class);
        logger.detachAppender(serviceLog);
    }

    @Test
    void dbFailure_returns503WithoutExceptionDetail() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException(LEAKY_MESSAGE));

        ResponseEntity<Map<String, String>> response = controller.status();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsOnly(
                Map.entry("status", "DOWN"),
                Map.entry("db", "disconnected")); // error 키 없음 — 예외 원문은 응답에 싣지 않는다
    }

    @Test
    void dbFailure_logsExceptionWithStacktraceExactlyOnce() throws Exception {
        SQLException cause = new SQLException(LEAKY_MESSAGE);
        when(dataSource.getConnection()).thenThrow(cause);

        controller.status();

        assertThat(serviceLog.list).hasSize(1);
        ILoggingEvent event = serviceLog.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).doesNotContain(LEAKY_MESSAGE); // 원문은 cause 몫
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo(LEAKY_MESSAGE); // stacktrace 보존
    }

    @Test
    void healthyDb_returns200WithUnchangedBody() throws Exception {
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));

        ResponseEntity<Map<String, String>> response = controller.status();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsOnly(
                Map.entry("status", "UP"),
                Map.entry("db", "connected"));
        assertThat(serviceLog.list).isEmpty();
    }
}
