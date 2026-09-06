package com.laimory.server.initializer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 앱 초기화 응답의 약관 그룹(#434). 서버는 알려주기만 하고 진행 차단은 클라이언트 책임이다 —
 * 이 값을 무시한 요청을 서버가 403으로 막지 않는다.
 */
@Schema(description = "앱 초기화 약관 상태")
public record InitializerTermsResponse(
        @Schema(description = "지금 동의가 필요한 약관 목록 — 현재 버전 동의가 없는 동의 대상 약관. "
                + "빈 배열이면 동의 절차 없이 진행한다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<AgreementRequiredTermResponse> agreementRequired
) {
}
