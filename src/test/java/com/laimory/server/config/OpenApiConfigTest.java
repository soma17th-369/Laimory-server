package com.laimory.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenApiConfig} 빈 검증 — 스프링 컨텍스트/인프라 없이 빈을 직접 빌드해 확인한다.
 */
class OpenApiConfigTest {

    private final OpenAPI openApi = new OpenApiConfig().laimoryOpenApi();

    @Test
    void serverUrl_상대경로_루트로_고정된다() {
        // Swagger UI가 현재 페이지 origin에 상대 해석 → dev(https)/로컬(http) mixed-content 방지
        assertThat(openApi.getServers()).hasSize(1);
        assertThat(openApi.getServers().get(0).getUrl()).isEqualTo("/");
    }

    @Test
    void bearerAuth_보안스킴을_선언한다() {
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("bearerAuth");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    void 공통응답_설명이_결과없는_성공의_null_body를_구분한다() {
        assertThat(openApi.getInfo().getDescription())
                .contains("반환할 결과가 없는 성공은 `body = null`")
                .contains("`header.code = 0`", "**code < 0이면 에러**")
                .doesNotContain("COMMON_0000", "ERROR_*");
    }
}
