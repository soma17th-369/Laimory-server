package com.laimory.server.admin;

import com.laimory.server.auth.security.ApiErrorResponseWriter;
import com.laimory.server.common.error.ExceptionType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 실제 수신 포트와 HTTP authority는 독립된 경계다. 비활성일 때도 관리자 경로를 닫는다. */
public final class AdminRequestGuardFilter extends OncePerRequestFilter {

    private final AdminServer adminServer;
    private final ApiErrorResponseWriter errors;

    public AdminRequestGuardFilter(AdminServer adminServer, ApiErrorResponseWriter errors) {
        this.adminServer = adminServer;
        this.errors = errors;
    }

    public static boolean isAdminRequest(HttpServletRequest request) {
        // Tomcat이 decode·normalize하고 path parameter를 제거한 servlet path를 사용한다.
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return path.equals("/admin") || path.startsWith("/admin/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isAdminRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int port = adminServer.boundPort();
        List<String> hosts = Collections.list(request.getHeaders("Host"));
        // Tomcat은 HTTP/2 :authority도 Servlet Host로 제공한다. exact 비교가 중복·잘못된 authority도 거절한다.
        if (port <= 0 || request.getLocalPort() != port || request.getHeader("X-Forwarded-For") != null
                || hosts.size() != 1 || !(hosts.getFirst().equals("localhost:" + port)
                || hosts.getFirst().equals("127.0.0.1:" + port))) {
            errors.write(request, response, ExceptionType.RESOURCE_NOT_FOUND);
            return;
        }
        if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
            List<String> origins = Collections.list(request.getHeaders("Origin"));
            if (origins.size() != 1 || !origins.getFirst().equals("http://" + hosts.getFirst())) {
                errors.write(request, response, ExceptionType.ADMIN_REQUEST_REJECTED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
