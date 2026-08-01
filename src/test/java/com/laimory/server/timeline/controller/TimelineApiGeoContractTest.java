package com.laimory.server.timeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Draft POST의 파생 지오코딩 상한·부분 실패 공개 계약을 어노테이션 수준에서 고정한다. */
class TimelineApiGeoContractTest {

    @Test
    void createDraftTask는_파생_고유좌표_상한과_400을_설명한다() {
        Method method = Arrays.stream(TimelineApi.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("createDraftTask"))
                .findFirst()
                .orElseThrow();

        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation.description())
                .contains("고유 좌표")
                .contains("최대 30개")
                .contains("sourceItems 배열 길이 제한이 아니라")
                .contains("startAt은 필수")
                .contains("endAt은 nullable");

        ApiResponse badRequest = Arrays.stream(method.getAnnotation(ApiResponses.class).value())
                .filter(response -> response.responseCode().equals("400"))
                .findFirst()
                .orElseThrow();
        assertThat(badRequest.description())
                .contains("-400")
                .contains("startAt 누락")
                .contains("고유 좌표 30개 초과");
    }

    @Test
    void createDraftTask는_지오코딩_품질거절의_두_오류코드를_설명한다() {
        Method method = Arrays.stream(TimelineApi.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("createDraftTask"))
                .findFirst()
                .orElseThrow();
        ApiResponse badGateway = Arrays.stream(method.getAnnotation(ApiResponses.class).value())
                .filter(response -> response.responseCode().equals("502"))
                .findFirst()
                .orElseThrow();

        assertThat(badGateway.description())
                .contains("20% 초과")
                .contains("연속 실패 3개")
                .contains("-1014")
                .contains("-1015");
    }
}
