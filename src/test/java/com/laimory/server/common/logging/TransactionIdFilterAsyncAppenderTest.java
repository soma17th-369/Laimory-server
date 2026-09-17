package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * access 로그가 운영 프로파일의 AsyncAppender(#497)를 거쳐 다른 스레드에서 써져도 MDC transactionId와
 * marker field가 보존되는지 고정한다. 인프라 0.
 *
 * <p>{@link TransactionIdFilter}는 완료 로그를 남긴 직후 {@code MDC.remove}하므로, enqueue 시점
 * ({@code prepareForDeferredProcessing})에 MDC가 캡처되지 않으면 워커가 쓰는 순간엔 tx가 없다.
 * {@link TransactionIdFilterTest}는 동기 ListAppender로 관측해 이 경로를 검증하지 못한다.
 * 설정값은 {@code logback-spring.xml}의 {@code ASYNC_JSON_CONSOLE}과 같게 둔다.
 */
class TransactionIdFilterAsyncAppenderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionIdFilter filter = new TransactionIdFilter(objectMapper);
    private final RecordingAppender sink = new RecordingAppender();
    private final AsyncAppender async = new AsyncAppender();

    @BeforeEach
    void attachAsyncAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        sink.setContext(context);
        sink.start();
        async.setContext(context);
        async.setQueueSize(1024);
        async.setNeverBlock(true);
        async.setMaxFlushTime(5000);
        async.addAppender(sink);
        async.start();
        ((Logger) LoggerFactory.getLogger("http.access")).addAppender(async);
    }

    @AfterEach
    void detachAsyncAppender() {
        ((Logger) LoggerFactory.getLogger("http.access")).detachAppender(async);
        async.stop(); // 이미 멈춘 경우 no-op
    }

    @Test
    void transactionIdAndFieldsSurviveDeferredWrite() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intro");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());
        String issued = response.getHeader("Transaction-Id");
        assertThat(MDC.get(TransactionIds.MDC_KEY)).isNull(); // 필터는 로그 호출 뒤 MDC를 이미 비웠다

        async.stop(); // maxFlushTime 안에서 큐를 비운 뒤 워커 종료 — 폴링 없이 결정적
        assertThat(sink.events).hasSize(1);
        assertThat(sink.writerThreads.get(0)).isNotEqualTo(Thread.currentThread()); // 실제로 지연 쓰기였다
        assertThat(sink.writerThreads.get(0).getName()).startsWith("AsyncAppender-Worker-");

        ILoggingEvent event = sink.events.get(0);
        assertThat(event.getMDCPropertyMap()).containsEntry(TransactionIds.MDC_KEY, issued); // enqueue 시점 캡처
        JsonNode json = encoded(event);
        assertThat(json.get("transactionId").asText()).isEqualTo(issued);
        assertThat(json.get("event").asText()).isEqualTo("http_request_completed");
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/intro");
        assertThat(json.get("status").asInt()).isEqualTo(200);
    }

    @Test
    void eachRequestKeepsItsOwnTransactionId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/intro"), first, new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/terms"), second, new MockFilterChain());
        async.stop();

        assertThat(sink.events).hasSize(2);
        assertThat(sink.events.get(0).getMDCPropertyMap().get(TransactionIds.MDC_KEY))
                .isEqualTo(first.getHeader("Transaction-Id"));
        assertThat(sink.events.get(1).getMDCPropertyMap().get(TransactionIds.MDC_KEY))
                .isEqualTo(second.getHeader("Transaction-Id"));
    }

    private JsonNode encoded(ILoggingEvent event) throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.setCustomFields("{\"service\":\"laimory\",\"environment\":\"dev\"}");
        encoder.start();
        try {
            return objectMapper.readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }

    /** 이벤트와 함께 실제로 쓴 스레드를 기록한다 — 지연 쓰기가 일어났음을 단언하기 위해. */
    private static final class RecordingAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final List<Thread> writerThreads = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
            writerThreads.add(Thread.currentThread());
        }
    }
}
