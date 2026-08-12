package com.laimory.server.timeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.user.CurrentSubject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 보호 API 17개의 인증 문서 계약을 어노테이션 수준에서 고정한다:
 * class-level {@code bearerAuth} security requirement, 401 {@code ERROR_2001} 응답 문서,
 * {@code @CurrentSubject SubjectId} owner의 OpenAPI 비노출({@code hidden = true} — 클라 입력 아님).
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
        assertThat(unauthorized.get(0).description()).contains("-2001");
    }

    @ParameterizedTest
    @MethodSource("protectedOperations")
    void everyProtectedOperation_hidesCurrentSubjectFromOpenApiParameters(Method method) {
        List<java.lang.reflect.Parameter> principals = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(CurrentSubject.class))
                .toList();

        assertThat(principals).hasSize(1);
        java.lang.reflect.Parameter principal = principals.get(0);
        assertThat(principal.getType()).isEqualTo(SubjectId.class);
        // principal은 클라이언트 입력이 아니다 — 생성된 OpenAPI parameter에 나타나면 안 된다.
        Parameter openApiParameter = principal.getAnnotation(Parameter.class);
        assertThat(openApiParameter).isNotNull();
        assertThat(openApiParameter.hidden()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void timelineOperations_doNotAdvertiseRetiredDateGuardError() {
        List<String> descriptions = Stream.of(TimelineApi.class, TimelineRecordApi.class)
                .flatMap(api -> Arrays.stream(api.getDeclaredMethods()))
                .map(method -> method.getAnnotation(ApiResponses.class))
                .flatMap(responses -> Arrays.stream(responses.value()))
                .map(ApiResponse::description)
                .toList();

        assertThat(descriptions).noneMatch(description -> description.contains("-1016"));
    }

    @org.junit.jupiter.api.Test
    void dailyRecordIdOperations_areDeprecatedAndPointToDateReplacement() throws NoSuchMethodException {
        Method getById = TimelineRecordApi.class.getDeclaredMethod(
                "getDailyTimeline", String.class, SubjectId.class, Long.class);
        Method deleteById = TimelineRecordApi.class.getDeclaredMethod(
                "deleteDailyRecord", String.class, SubjectId.class, Long.class);

        for (Method method : List.of(getById, deleteById)) {
            Operation operation = method.getAnnotation(Operation.class);
            assertThat(operation.deprecated()).isTrue();
            assertThat(operation.description()).contains("/daily-records/{recordDate}");
        }
    }

    @org.junit.jupiter.api.Test
    void protectedOperationCount_isSeventeen() {
        // timeline 15개(날짜 GET/DELETE·저장 POST·Event 단건 GET·Event Item 연결 해제 포함)
        // + push-registrations PUT/DELETE 2개.
        assertThat(protectedOperations().count()).isEqualTo(17);
    }
}
