package com.laimory.server.common.logging;

import static net.logstash.logback.argument.StructuredArguments.fields;

import com.laimory.server.common.error.ExceptionType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청의 최전방에서 transactionId를 부여하고 요청당 정확히 한 줄의 access 로그를 남기는 필터.
 *
 * <ul>
 *   <li>요청마다 새 UUIDv7을 발급해 MDC에 넣고, 같은 값을 응답 헤더 {@code Transaction-Id}로
 *       노출한다(클라이언트 노출 채널은 이 헤더 하나 — 외부 문자열이 MDC로 들어올 통로는 없다).
 *       헤더는 chain 진입 전에 설정하므로 필터 단계에서 직접 쓰는 에러 응답에도 실리고, 미처리 예외의
 *       /error 디스패치에서도 유지된다(컨테이너는 body 버퍼만 리셋).</li>
 *   <li>완료 로그 레벨은 status가 아니라 예외 처리 지점이 attribute로 심은 {@link ExceptionType}의
 *       {@code logLevel()}이 정한다(access 로그 레벨의 SSOT — status는 클라이언트 계약, 레벨은
 *       서버 관점 심각도로 독립 축). 에러 없는 요청은 INFO.</li>
 *   <li>{@link ExcludedPaths}(헬스체크·favicon 등 신호 없는 트래픽)는 정상 완료 시 로그를 남기지
 *       않는다. 에러·미처리 예외는 경로와 무관하게 남고, tx 발급·MDC·응답 헤더도 항상 유지된다.</li>
 *   <li>미처리 예외가 필터까지 전파되면 ERROR + effective status 500으로 기록 후 그대로 rethrow한다
 *       — status 매핑이 아니라 "아무도 처리 못 했다"는 사실 기반.</li>
 * </ul>
 *
 * <p>{@code OncePerRequestFilter}는 ERROR/ASYNC 디스패치를 기본 skip하므로 중복 로그가 없다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionIdFilter extends OncePerRequestFilter {

    /** access 로그 전용 로거 — 클래스 로거와 분리해 라우팅/레벨을 독립 제어한다. */
    private static final Logger log = LoggerFactory.getLogger("http.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String transactionId = TransactionIds.newId();
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
        String path = request.getRequestURI();                    // query string 제외(presigned 서명·토큰 유출 방지)
        ExceptionType type = (ExceptionType) request.getAttribute(RequestLogAttributes.EXCEPTION_TYPE);
        if (caught == null && type == null && ExcludedPaths.contains(path)) {
            return; // 정상 완료한 제외 경로만 생략 — 에러는 경로와 무관하게 남긴다
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        int status = caught != null ? 500 : response.getStatus(); // 예외 전파 시 아직 200인 status 오기록 방지
        String errorDetail = (String) request.getAttribute(RequestLogAttributes.ERROR_DETAIL);

        // fields(): JSON encoder에선 record 프로퍼티가 top-level 필드로 전개되고(타입 보존),
        // 텍스트 패턴에선 record toString으로 찍힌다. 필드 추가는 HttpRequestLog 한 곳이면 된다.
        HttpRequestLog entry = HttpRequestLog.of(request.getMethod(), path, status, latencyMs, type, errorDetail);
        log.atLevel(resolveLevel(caught, type)).setCause(caught).log("{}", fields(entry));
    }

    /** 레벨은 status가 아니라 ExceptionType이 정한다(access 로그 레벨의 SSOT). */
    private static Level resolveLevel(Throwable caught, ExceptionType type) {
        if (caught != null) {
            return Level.ERROR; // 핸들러까지 뚫고 나온 미처리 예외 — 매핑이 아니라 사실 기반
        }
        return type != null ? type.logLevel() : Level.INFO;
    }
}
