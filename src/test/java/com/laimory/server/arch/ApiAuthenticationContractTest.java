package com.laimory.server.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.push.controller.PushRegistrationApi;
import com.laimory.server.terms.controller.PublicTermApi;
import com.laimory.server.terms.controller.TermAgreementApi;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * {@code /a/api} 보호 API 21개의 인증 문서 계약을 어노테이션 수준에서 고정한다:
 * class-level {@code bearerAuth} security requirement, 401 {@code -2001} 응답 문서,
 * principal parameter의 OpenAPI 비노출({@code hidden = true} — 클라 입력 아님).
 *
 * <p>principal은 operation마다 정확히 하나이며, 형태는 API 종류가 결정한다 — 콘텐츠·push API는
 * {@code @CurrentSubject UUID}, 회원 account API는 {@code @AuthenticationPrincipal Long}이다.
 * 새 보호 API는 {@link #EXPECTED_PRINCIPALS}에 기대 principal 형태와 함께 등록한다(either-or 허용이
 * 아니라 API별 고정 — 콘텐츠 API가 실수로 raw userId를 받는 회귀를 빌드에서 차단).
 * (timeline 전용이던 {@code TimelineApiAuthenticationContractTest}를 공용 계약으로 일반화해 옮겼다 —
 * timeline 전용 문서 계약은 {@code timeline.controller.TimelineApiDocumentationContractTest}에 남긴다.)
 */
class ApiAuthenticationContractTest {

    /** 보호 operation의 principal 형태. */
    private enum PrincipalKind {
        /** 콘텐츠·push — hidden {@code @CurrentSubject UUID subjectId}(MVC resolver가 subject로 변환). */
        CONTENT_SUBJECT,
        /** 회원 account — hidden {@code @AuthenticationPrincipal Long userId}(subject 변환 없음). */
        ACCOUNT_USER_ID
    }

    /** 보호 API 클래스 → 기대 principal 형태. 새 보호 API 등록 시 기대 형태를 여기서 함께 선언한다. */
    private static final Map<Class<?>, PrincipalKind> EXPECTED_PRINCIPALS = Map.of(
            TimelineApi.class, PrincipalKind.CONTENT_SUBJECT,
            TimelineRecordApi.class, PrincipalKind.CONTENT_SUBJECT,
            PushRegistrationApi.class, PrincipalKind.CONTENT_SUBJECT,
            UserApi.class, PrincipalKind.ACCOUNT_USER_ID,
            TermAgreementApi.class, PrincipalKind.ACCOUNT_USER_ID);

    static Stream<Method> protectedOperations() {
        return EXPECTED_PRINCIPALS.keySet().stream()
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
    void everyProtectedOperation_hasExactlyOneHiddenPrincipalOfDeclaredKind(Method method) {
        PrincipalKind expected = EXPECTED_PRINCIPALS.get(method.getDeclaringClass());

        List<java.lang.reflect.Parameter> principals = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(CurrentSubject.class)
                        || parameter.isAnnotationPresent(AuthenticationPrincipal.class))
                .toList();

        assertThat(principals).hasSize(1);
        java.lang.reflect.Parameter principal = principals.get(0);
        // API 종류가 principal 형태를 결정한다 — either-or가 아니라 선언된 기대 형태만 허용한다.
        switch (expected) {
            case CONTENT_SUBJECT -> {
                assertThat(principal.isAnnotationPresent(CurrentSubject.class)).isTrue();
                assertThat(principal.isAnnotationPresent(AuthenticationPrincipal.class)).isFalse();
                assertThat(principal.getType()).isEqualTo(UUID.class);
            }
            case ACCOUNT_USER_ID -> {
                assertThat(principal.isAnnotationPresent(AuthenticationPrincipal.class)).isTrue();
                assertThat(principal.isAnnotationPresent(CurrentSubject.class)).isFalse();
                assertThat(principal.getType()).isEqualTo(Long.class);
            }
        }
        // principal은 클라이언트 입력이 아니다 — 생성된 OpenAPI parameter에 나타나면 안 된다.
        Parameter openApiParameter = principal.getAnnotation(Parameter.class);
        assertThat(openApiParameter).isNotNull();
        assertThat(openApiParameter.hidden()).isTrue();
    }

    @Test
    void protectedOperationCount_isTwentyTwo() {
        // timeline 16개(날짜 GET/DELETE·저장 POST·Event 단건 GET·Event Item 연결 해제·월별 GET 포함)
        // + push-registrations PUT/DELETE 2개 + users GET /me·DELETE /me 2개(#305 탈퇴 추가)
        // + terms agreements GET/POST 2개.
        assertThat(protectedOperations().count()).isEqualTo(22);
    }

    @Test
    void publicTermApi_staysOutsideBearerContract() {
        // 공개 약관 조회는 보호 operation 목록 밖이다 — class-level bearer 문서가 없어야
        // public prefix(/api)와 문서·실제 enforcement가 어긋나지 않는다.
        assertThat(PublicTermApi.class.getAnnotation(SecurityRequirement.class)).isNull();
    }
}
