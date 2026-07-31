package com.laimory.server.auth.security;

import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.common.logging.RequestLogAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /a/api} 요청의 Bearer access JWT를 검증해 {@code Long} userId principal을 SecurityContext에 넣는다.
 *
 * <p>이 필터는 인증 "시도"만 한다 — 헤더 부재·형식 불량·검증 실패는 사유 구분 없이 context 없이 chain을
 * 진행시키고, 거절(401 {@code -2001})은 인가 단계의 {@link ApiAuthenticationEntryPoint}가 담당한다.
 * 사유는 클라이언트 행동을 바꾸지 않으므로(전부 재인증 경로) 응답·로그에 상세를 남기지 않는다.
 *
 * <p>principal은 별도 래퍼 없이 {@code Long} userId 그대로다 — 컨트롤러의
 * {@code @AuthenticationPrincipal Long userId}와 1:1로 맞춘다. token 원문은 credentials나
 * request attribute에 보존하지 않는다(유출면 최소화). 다만 userId는
 * {@code RequestLogAttributes.USER_ID} attribute에도 심어 access 로그가 인증 주체를 남길 수 있게 한다 —
 * 완료 로그 시점에는 {@code SecurityContextHolder}가 이미 비워져 있기 때문이다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokens jwtTokens;

    public JwtAuthenticationFilter(JwtTokens jwtTokens) {
        this.jwtTokens = jwtTokens;
    }

    /** 정확히 {@code /a/api}와 하위 경로에서만 동작한다({@code /a/apiary} 같은 문자열 prefix 미매칭). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.equals(ApiUrls.AUTHENTICATED_API_PREFIX)
                && !uri.startsWith(ApiUrls.AUTHENTICATED_API_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveBearerToken(request);
        if (token != null) {
            jwtTokens.parseUserId(token).ifPresent(userId -> {
                // 새 context 생성 — 공유 인스턴스 재사용으로 다른 스레드/요청에 인증이 새는 것을 방지(공식 권고).
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
                SecurityContextHolder.setContext(context);
                // access 로그용 사본 — 완료 로그는 context가 비워진 뒤 찍히므로 SecurityContext로는 못 읽는다.
                request.setAttribute(RequestLogAttributes.USER_ID, userId);
            });
        }
        chain.doFilter(request, response);
    }

    /** {@code Authorization: Bearer <token>}에서 non-blank token만 추출한다. scheme은 대소문자 무관(RFC 7235). */
    private String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null
                || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
