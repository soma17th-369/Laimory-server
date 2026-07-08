package com.laimory.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 문서 메타 정보(타이틀·공통 규칙 안내).
 *
 * <p>노출 여부는 {@code springdoc.api-docs.enabled}/{@code springdoc.swagger-ui.enabled}가 결정한다
 * (기본 off — dev 배포와 로컬 docker 프로필만 on, application.properties 참고).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI laimoryOpenApi() {
        return new OpenAPI()
                // 자체 access token(Bearer) 입력용 스킴 — Swagger UI Authorize 버튼에서 토큰을 넣어 try-out.
                // (/a/api 강제 전환(#108) 전까지는 무토큰도 동작하지만, 로그인 흐름 검증용으로 미리 노출.)
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("자체 access token — POST /api/v1/auth/token 으로 발급")))
                .info(new Info()
                .title("Laimory API")
                .version("v1")
                .description("""
                        Laimory 안드로이드 앱 백엔드 API.

                        ## 공통 응답 envelope
                        앱-facing API의 모든 응답(성공·에러)은 `ApiResponse{header{code, message, transactionId}, body}` 형태다.
                        - **성공**: `header.code = COMMON_0000`, 결과는 `body`에 담긴다.
                        - **에러**: `header.code = ERROR_*`, `body = null`. **code가 `ERROR_`로 시작하면 에러**로 분기한다.
                        - `header.message`는 로캘(Accept-Language, 기본 한국어)이 적용된 사용자 노출 문구다 — \
                        클라이언트 분기는 message가 아니라 code로 한다.
                        - `header.transactionId`: 요청 추적 ID(UUID). 문의·버그 리포트 시 함께 전달한다.

                        ## 경로 prefix
                        - `/api/{applicationVersion}` — 공개(인증 불필요)
                        - `/a/api/{applicationVersion}` — 사용자 인증 필요(인증 도입 전까지는 고정 사용자로 동작)
                        - `/s/api/{applicationVersion}` — 서버간 통신(AI 콜백 등, 엔드포인트별 자체 인증)
                        """));
    }
}
