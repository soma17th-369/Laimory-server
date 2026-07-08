package com.laimory.server.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.logging.RequestLogAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인 시작({@code GET /oauth2/authorization/*})의 {@code app_challenge} 필수 검증 + 세션 보관.
 *
 * <p>핸드오프 PKCE의 절반이다: 앱이 로그인 전 생성한 verifier의 해시(challenge)를 여기서 세션에 묶어두고,
 * 로그인 성공 시 app_code에 바인딩한다(토큰 교환 때 verifier 대조). 누락/형식 불량이면 로그인 시작 자체를
 * 400으로 거절한다 — challenge 없는 app_code는 발급 즉시 고아라서 일찍 끊는 게 낫다.
 *
 * <p>커스텀 {@code AuthorizationRequestResolver}에서 throw하는 대신 별도 필터인 이유: resolver 예외는
 * 필터체인 밖으로 전파돼 500으로 나간다 — 여기서는 envelope 400을 직접 쓴다(필터 단계라
 * {@code GlobalExceptionHandler} 미도달). 세션은 Spring Session(Redis)이라 콜백이 다른 인스턴스에
 * 떨어져도 공유된다.
 */
public class AppChallengeFilter extends OncePerRequestFilter {

    public static final String APP_CHALLENGE_SESSION_ATTRIBUTE = "laimory.auth.appChallenge";
    public static final String APP_CHALLENGE_PARAMETER = "app_challenge";

    /** challenge = base64url(sha256(verifier)), 패딩 없음 → 항상 43자. */
    private static final Pattern CHALLENGE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final String AUTHORIZATION_BASE_PATH = "/oauth2/authorization/";

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public AppChallengeFilter(MessageSource messageSource, ObjectMapper objectMapper) {
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AUTHORIZATION_BASE_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String appChallenge = request.getParameter(APP_CHALLENGE_PARAMETER);
        if (appChallenge == null || !CHALLENGE_PATTERN.matcher(appChallenge).matches()) {
            writeBadRequest(request, response);
            return;
        }
        request.getSession(true).setAttribute(APP_CHALLENGE_SESSION_ATTRIBUTE, appChallenge);
        chain.doFilter(request, response);
    }

    /** 입력 검증 실패라 제네릭 400(ERROR_0400) envelope — 필터 단계라 직접 작성한다. */
    private void writeBadRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setAttribute(RequestLogAttributes.ERROR_CODE, ErrorCode.ERROR_0400.code());
        // 필터 단계는 MVC LocaleResolver(spring.web.locale=ko) 미적용 — 헤더 없으면 한국어로 직접 폴백.
        Locale locale = request.getHeader("Accept-Language") == null ? Locale.KOREAN : request.getLocale();
        String message = messageSource.getMessage(ErrorCode.ERROR_0400.code(), null, locale);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.ERROR_0400.code(), message));
    }
}
