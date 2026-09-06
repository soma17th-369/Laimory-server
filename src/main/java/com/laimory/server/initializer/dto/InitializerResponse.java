package com.laimory.server.initializer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 앱 시작 시 필요한 사용자별 초기 상태(#382). 담긴 값은 온보딩 완료 여부와 약관 초기 상태(#434)이며,
 * 이후 초기 상태가 늘어도 기존 field의 의미와 호환성은 유지한다. 그룹(depth)은 미래에도 여러 field를
 * 가질 도메인에만 만든다 — 단일 값으로 남을 도메인은 최상위 평면 field로 둔다(#434 결정).
 *
 * <p>field는 always-present라 required 목록도 전체여야 한다 — 일부만 선언하면 생성된 클라이언트 모델에서
 * 나머지가 nullable로 잘못 나온다. 중첩 DTO에도 같은 규칙을 적용한다.
 */
@Schema(description = "앱 초기화 조회 응답")
public record InitializerResponse(
        @Schema(description = "앱 온보딩 완료 여부(저장값 그대로 — 약관 동의 이력에서 계산하지 않는다)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean onboardingCompleted,
        @Schema(description = "약관 초기 상태", requiredMode = Schema.RequiredMode.REQUIRED)
        InitializerTermsResponse terms
) {
}
