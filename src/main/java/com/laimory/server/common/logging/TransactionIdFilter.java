package com.laimory.server.common.logging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청의 최전방에서 transactionId를 부여하고 요청당 정확히 한 줄의 access 로그를 남기는 필터.
 *
 * <ul>
 *   <li>요청 헤더 {@code X-Transaction-Id}가 유효한 UUIDv7이면 재사용, 아니면 새로 발급한다.</li>
 *   <li>MDC와 응답 헤더에 세팅한다 — {@code doFilter} 전에 세팅하므로 에러 디스패치 응답에도 실린다
 *       ({@code sendError}는 버퍼만 비우고 커스텀 헤더는 보존).</li>
 *   <li>완료 로그 레벨은 status 기반: 5xx ERROR / 4xx WARN / 그 외 INFO. 순수 헬스체크 전용
 *       {@code /status}만 DEBUG로 강등한다(/api/v{n}/intro는 실사용 API라 INFO 유지).</li>
 *   <li>미처리 예외가 필터까지 전파되면 effective status 500으로 기록 후 그대로 rethrow한다.</li>
 * </ul>
 *
 * <p>{@code OncePerRequestFilter}는 ERROR/ASYNC 디스패치를 기본 skip하므로 중복 로그가 없다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionIdFilter extends OncePerRequestFilter {

    /** access 로그 전용 로거 — 클래스 로거와 분리해 라우팅/레벨을 독립 제어한다. */
    private static final Logger log = LoggerFactory.getLogger("http.access");

    /** 순수 헬스체크 전용 경로. 정상 응답이면 DEBUG로 강등해 노이즈를 줄인다. */
    private static final String QUIET_PATH = "/status";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TransactionIds.HEADER_NAME);
        String transactionId = TransactionIds.isValidV7(incoming) ? incoming : TransactionIds.newId();
        MDC.put(TransactionIds.MDC_KEY, transactionId);
        response.setHeader(TransactionIds.HEADER_NAME, transactionId);

        long start = System.nanoTime();
        Throwable caught = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            caught = t;
            throw t;
        } finally {
            logCompletion(request, response, start, caught);
            MDC.remove(TransactionIds.MDC_KEY);
        }
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long start, Throwable caught) {
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        int status = caught != null ? 500 : response.getStatus(); // 예외 전파 시 아직 200인 status 오기록 방지
        String path = request.getRequestURI();                    // query string 제외(서명·토큰 유출 방지)

        // kv(): 텍스트 패턴에선 "key=value"로, JSON encoder에선 타입 보존 필드로 동시 출력된다.
        Object[] fields = {
                kv("event", "http_request_completed"),
                kv("method", request.getMethod()),
                kv("path", path),
                kv("status", status),
                kv("latencyMs", latencyMs),
                kv("errorCode", request.getAttribute(RequestLogAttributes.ERROR_CODE)),
        };
        if (caught != null) {
            log.atError().setCause(caught).log("{} {} {} {} {} {}", fields);
        } else if (status >= 500) {
            log.error("{} {} {} {} {} {}", fields);
        } else if (status >= 400) {
            log.warn("{} {} {} {} {} {}", fields);
        } else if (QUIET_PATH.equals(path)) {
            log.debug("{} {} {} {} {} {}", fields);
        } else {
            log.info("{} {} {} {} {} {}", fields);
        }
    }
}
