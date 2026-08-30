package com.laimory.server.terms;

import com.laimory.server.terms.service.TermsEnforcementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code /a/api} HandlerMethod의 필수 약관 gate — Security chain의 bearer 인증(401)을 통과한 요청만
 * 도달하며, controller 진입 전에 동의 상태를 검사해 미동의는 403({@code -3001})으로 거절한다.
 *
 * <p>현재 필수 문서 전부에 대한 동의를 검사한다. {@link LoginTermsExempt}가 붙은 operation만
 * gate를 면제한다.
 * annotation은 {@code *Api} interface method에 선언한다 — {@link HandlerMethod}의 annotation 탐색
 * (find semantics)이 구현 method의 interface 선언까지 본다.
 *
 * <p>비 HandlerMethod(미매핑 경로 등)와 무인증 요청(SecurityConfig가 이미 거절 — 방어적 분기)은
 * 검사하지 않는다. token refresh/logout은 public auth 경로라 이 interceptor 대상이 아니다.
 */
@Component
public class TermsEnforcementInterceptor implements HandlerInterceptor {

    private final ObjectProvider<TermsEnforcementService> termsEnforcementServiceProvider;

    public TermsEnforcementInterceptor(ObjectProvider<TermsEnforcementService> termsEnforcementServiceProvider) {
        this.termsEnforcementServiceProvider = termsEnforcementServiceProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        Long userId = authenticatedUserId();
        if (userId == null) {
            return true;
        }
        if (handlerMethod.getMethodAnnotation(LoginTermsExempt.class) == null) {
            enforce(userId);
        }
        return true;
    }

    private void enforce(Long userId) {
        TermsEnforcementService termsEnforcementService = termsEnforcementServiceProvider.getIfAvailable();
        if (termsEnforcementService == null) {
            // 구성 오류는 gate를 조용히 여는 대신 실패시킨다(CurrentSubjectArgumentResolver 선례).
            throw new IllegalStateException("terms enforcement service is unavailable");
        }
        termsEnforcementService.requireAgreements(userId);
    }

    private static Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            return null;
        }
        return userId;
    }
}
