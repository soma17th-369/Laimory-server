package com.laimory.server.auth.security;

import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.RequestLogAttributes;
import com.laimory.server.user.service.UserAccountAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /a/api} 요청의 Bearer access JWT를 검증해 {@code Long} userId principal을 SecurityContext에 넣는다.
 *
 * <p>JWT 파싱 성공만으로는 인증이 성립하지 않는다 — 요청마다 {@link UserAccountAccessService#isActive}로
 * 회원 행이 {@code ACTIVE}인지 확인한 경우에만 SecurityContext를 만든다(#305 §5.3). 회원 없음과
 * {@code WITHDRAWAL_PENDING}은 token 상세와 구분하지 않고 context 없이 통과시켜 인가 단계의 기존
 * 401 {@code -2001}로 수렴한다. 이 검사는 #429부터 공유 Redis 캐시({@code RedisActiveStatusCache},
 * ACTIVE=true만·탈퇴 시 DEL·TTL 안전망)를 탄다 — 탈퇴 커밋·evict 뒤 시작된 요청은 결정적으로
 * 차단되고, evict 유실·적재 경합의 한시적 stale 인증은 #429 "보안 정책 개정"이 명시적으로 허용한다
 * (커밋 전 in-flight 작업의 산물만 노출되고, 각 token은 발급 시각+수명까지, 회전 사슬은 1회 종결).
 *
 * <p>이 필터는 인증 "시도"만 한다 — 헤더 부재·형식 불량·검증 실패는 사유 구분 없이 context 없이 chain을
 * 진행시키고, 거절(401 {@code -2001})은 인가 단계의 {@link ApiAuthenticationEntryPoint}가 담당한다.
 * 사유는 클라이언트 행동을 바꾸지 않으므로(전부 재인증 경로) 응답·로그에 상세를 남기지 않는다.
 * 단 하나의 예외가 상태 조회의 DB 장애다 — 장애를 조용한 401(credential 오류)로 숨기지 않고
 * {@link ApiErrorResponseWriter}로 fail-closed 500 {@code -500} envelope와 ERROR 관측(access 로그
 * attribute + stacktrace 로그)을 남긴 뒤 chain을 중단한다. 이 계약은 <b>캐시 miss 경로</b>에서
 * 유지된다 — warm hit는 DB를 호출하지 않아 그 요청에서 DB 장애가 관측되지 않고, Redis 장애는
 * 캐시가 miss로 강등해 DB 직행한다(#429 장애 의미론).
 *
 * <p>principal은 별도 래퍼 없이 {@code Long} userId 그대로다 — 컨트롤러의
 * {@code @AuthenticationPrincipal Long userId}와 1:1로 맞춘다. token 원문은 credentials나
 * request attribute에 보존하지 않는다(유출면 최소화). userId는 active 인증이 성립한 뒤에만
 * {@code RequestLogAttributes.USER_ID} attribute에 심어 access 로그가 인증 주체를 남길 수 있게 한다 —
 * 완료 로그 시점에는 {@code SecurityContextHolder}가 이미 비워져 있기 때문이다.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokens jwtTokens;
    private final UserAccountAccessService userAccountAccessService;
    private final ApiErrorResponseWriter apiErrorResponseWriter;

    public JwtAuthenticationFilter(JwtTokens jwtTokens,
                                   UserAccountAccessService userAccountAccessService,
                                   ApiErrorResponseWriter apiErrorResponseWriter) {
        this.jwtTokens = jwtTokens;
        this.userAccountAccessService = userAccountAccessService;
        this.apiErrorResponseWriter = apiErrorResponseWriter;
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
        Optional<Long> parsedUserId = token == null ? Optional.empty() : jwtTokens.parseUserId(token);
        if (parsedUserId.isPresent()) {
            long userId = parsedUserId.get();
            boolean active;
            try {
                active = userAccountAccessService.isActive(userId);
            } catch (RuntimeException e) {
                // DB 장애를 credential 오류(401)로 숨기지 않는다 — fail-closed 500 + ERROR 관측 후 chain 중단.
                // catch-all(GlobalExceptionHandler)처럼 stacktrace는 여기서 남긴다(필터 단계 미도달).
                log.error("account status lookup failed during authentication: type={}",
                        e.getClass().getName(), e);
                apiErrorResponseWriter.write(request, response, ExceptionType.UNEXPECTED_ERROR);
                return;
            }
            if (active) {
                // 새 context 생성 — 공유 인스턴스 재사용으로 다른 스레드/요청에 인증이 새는 것을 방지(공식 권고).
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
                SecurityContextHolder.setContext(context);
                // access 로그용 사본 — active 인증이 성립한 경우에만 심는다(완료 로그는 context가 비워진 뒤 실행).
                request.setAttribute(RequestLogAttributes.USER_ID, userId);
            }
            // inactive(회원 없음/탈퇴): context 없이 통과 → 인가 단계 401 -2001 수렴(존재 비노출).
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
