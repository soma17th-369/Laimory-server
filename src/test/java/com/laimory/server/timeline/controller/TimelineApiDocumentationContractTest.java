package com.laimory.server.timeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * timeline API 전용 문서 계약을 고정한다 — 폐기된 {@code -1016} 에러 미광고와 ID 기반 deprecated
 * operation의 날짜 경로 안내. 보호 operation 공통 인증 문서 계약(bearerAuth·401·hidden principal·개수)은
 * {@link com.laimory.server.arch.ApiAuthenticationContractTest}가 소유한다.
 */
class TimelineApiDocumentationContractTest {

    @Test
    void timelineOperations_doNotAdvertiseRetiredDateGuardError() {
        List<String> descriptions = Stream.of(TimelineApi.class, TimelineRecordApi.class)
                .flatMap(api -> Arrays.stream(api.getDeclaredMethods()))
                .map(method -> method.getAnnotation(ApiResponses.class))
                .flatMap(responses -> Arrays.stream(responses.value()))
                .map(ApiResponse::description)
                .toList();

        assertThat(descriptions).noneMatch(description -> description.contains("-1016"));
    }

    @Test
    void dailyRecordIdOperations_areDeprecatedAndPointToDateReplacement() throws NoSuchMethodException {
        Method getById = TimelineRecordApi.class.getDeclaredMethod(
                "getDailyTimeline", String.class, UUID.class, Long.class);
        Method deleteById = TimelineRecordApi.class.getDeclaredMethod(
                "deleteDailyRecord", String.class, UUID.class, Long.class);

        for (Method method : List.of(getById, deleteById)) {
            Operation operation = method.getAnnotation(Operation.class);
            assertThat(operation.deprecated()).isTrue();
            assertThat(operation.description()).contains("/daily-records/{recordDate}");
        }
    }
}
