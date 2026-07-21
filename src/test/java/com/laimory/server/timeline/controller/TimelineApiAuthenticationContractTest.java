package com.laimory.server.timeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 보호 API 9개의 인증 문서 계약을 어노테이션 수준에서 고정한다:
 * class-level {@code bearerAuth} security requirement, 401 {@code ERROR_2001} 응답 문서,
 * {@code @AuthenticationPrincipal Long} principal의 OpenAPI 비노출({@code hidden = true} — 클라 입력 아님).
 */
class TimelineApiAuthenticationContractTest {

    static Stream<Method> protectedOperations() {
        return Stream.of(TimelineApi.class, TimelineRecordApi.class,
                        com.laimory.server.push.controller.PushRegistrationApi.class)
                .flatMap(api -> Arrays.stream(api.getDeclaredMethods()))
                .filter(method -> !method.isSynthetic());
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void everyProtectedOperation_declaresBearerAuthOnInterface(Method method) {
        SecurityRequirement requirement = method.getDeclaringClass().getAnnotation(SecurityRequirement.class);

        assertThat(requirement).isNotNull();
        assertThat(requirement.name()).isEqualTo("bearerAuth");
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void everyProtectedOperation_documents401WithError2001(Method method) {
        ApiResponses responses = method.getAnnotation(ApiResponses.class);

        assertThat(responses).isNotNull();
        List<ApiResponse> unauthorized = Arrays.stream(responses.value())
                .filter(response -> "401".equals(response.responseCode()))
                .toList();
        assertThat(unauthorized).hasSize(1);
        assertThat(unauthorized.get(0).description()).contains("ERROR_2001");
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void everyProtectedOperation_hidesLongPrincipalFromOpenApiParameters(Method method) {
        List<java.lang.reflect.Parameter> principals = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(AuthenticationPrincipal.class))
                .toList();

        assertThat(principals).hasSize(1);
        java.lang.reflect.Parameter principal = principals.get(0);
        assertThat(principal.getType()).isEqualTo(Long.class);
        assertThat(principal.getAnnotation(AuthenticationPrincipal.class).errorOnInvalidType()).isTrue();
        // principal은 클라이언트 입력이 아니다 — 생성된 OpenAPI parameter에 나타나면 안 된다.
        Parameter openApiParameter = principal.getAnnotation(Parameter.class);
        assertThat(openApiParameter).isNotNull();
        assertThat(openApiParameter.hidden()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void protectedOperationCount_isNine() {
        // timeline 7개 + push-registrations PUT/DELETE 2개.
        assertThat(protectedOperations().count()).isEqualTo(9);
    }
}
