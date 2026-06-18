package com.laimory.server.timeline.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 내부 콜백 공유 secret 검증. {@code /internal/**} 요청의 X-Internal-Secret 헤더를 상수시간 비교한다.
 *
 * <p>누락/불일치면 401(요청 위조 방어 — 서버↔서버라 CSRF 아님). 콜백 공유 secret '하나'만 책임진다 —
 * OAuth2 redirect·사용자 인증·웹훅 서명 등 다른 검증은 각자(주로 Spring Security)에서 처리한다.
 */
@Component
public class CallbackSecretInterceptor implements HandlerInterceptor {

    private static final String SECRET_HEADER = "X-Internal-Secret";

    private final byte[] expectedSecret;

    public CallbackSecretInterceptor(@Value("${internal.callback.secret}") String expectedSecret) {
        this.expectedSecret = expectedSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String provided = request.getHeader(SECRET_HEADER);
        if (provided == null
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal secret");
        }
        return true;
    }
}
