package com.laimory.server.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.push.controller.PushRegistrationApi;
import com.laimory.server.timeline.controller.TimelineApi;
import com.laimory.server.timeline.controller.TimelineRecordApi;
import com.laimory.server.user.CurrentSubject;
import com.laimory.server.user.controller.UserApi;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * {@code /a/api} 보호 API 19개의 인증 문서 계약을 어노테이션 수준에서 고정한다:
 * class-level {@code bearerAuth} security requirement, 401 {@code -2001} 응답 문서,
 * principal parameter의 OpenAPI 비노출({@code hidden = true} — 클라 입력 아님).
 *
 * <p>principal은 operation마다 정확히 하나이며, 콘텐츠·push API의 {@code @CurrentSubject UUID} 또는
 * 회원 account API의 {@code @AuthenticationPrincipal Long} 중 정확히 한 형태다.
 * (timeline 전용이던 {@code TimelineApiAuthenticationContractTest}를 공용 계약으로 일반화해 옮겼다 —
 * timeline 전용 문서 계약은 {@code timeline.controller.TimelineApiDocumentationContractTest}에 남긴다.)
 */
class ApiAuthenticationContractTest {

    static Stream<Method> protectedOperations() {
        return Stream.of(TimelineApi.class, TimelineRecordApi.class, PushRegistrationApi.class, UserApi.class)
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
        assertThat(unauthorized.get(0).description()).contains("-2001");
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void everyProtectedOperation_hasExactlyOneHiddenPrincipalParameter(Method method) {
        List<java.lang.reflect.Parameter> principals = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(CurrentSubject.class)
                        || parameter.isAnnotationPresent(AuthenticationPrincipal.class))
                .toList();

        assertThat(principals).hasSize(1);
        java.lang.reflect.Parameter principal = principals.get(0);
        // 두 형태 중 정확히 하나 — 콘텐츠·push는 subject UUID, 회원 account는 raw Long userId.
        if (principal.isAnnotationPresent(CurrentSubject.class)) {
            assertThat(principal.isAnnotationPresent(AuthenticationPrincipal.class)).isFalse();
            assertThat(principal.getType()).isEqualTo(UUID.class);
        } else {
            assertThat(principal.getType()).isEqualTo(Long.class);
        }
        // principal은 클라이언트 입력이 아니다 — 생성된 OpenAPI parameter에 나타나면 안 된다.
        Parameter openApiParameter = principal.getAnnotation(Parameter.class);
        assertThat(openApiParameter).isNotNull();
        assertThat(openApiParameter.hidden()).isTrue();
    }

    @Test
    void protectedOperationCount_isNineteen() {
        // timeline 16개(날짜 GET/DELETE·저장 POST·Event 단건 GET·Event Item 연결 해제·월별 GET 포함)
        // + push-registrations PUT/DELETE 2개 + users GET /me 1개.
        assertThat(protectedOperations().count()).isEqualTo(19);
    }
}
