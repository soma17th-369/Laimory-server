package com.laimory.server.initializer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 앱 시작 시 필요한 사용자별 초기 상태(#382). 지금 담긴 값은 온보딩 완료 여부 하나이며, 이후 초기 상태가
 * 늘어도 기존 field의 의미와 호환성은 유지한다.
 *
 * <p>field는 always-present라 required 목록도 전체여야 한다 — 일부만 선언하면 생성된 클라이언트 모델에서
 * 나머지가 nullable로 잘못 나온다.
 */
@Schema(description = "앱 초기화 조회 응답")
public record InitializerResponse(
        @Schema(description = "앱 온보딩 완료 여부(저장값 그대로 — 약관 동의 이력에서 계산하지 않는다)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean onboardingCompleted
) {
}
