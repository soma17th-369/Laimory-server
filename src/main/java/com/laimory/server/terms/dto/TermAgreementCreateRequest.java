package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 약관 동의 일괄 등록 요청 — 수락 시각은 클라이언트가 보내지 않는다(서버가 기록).
 * 배열은 non-empty여야 하고 동일 {@code (termType, version)} 중복은 400이다.
 */
@Schema(description = "약관 동의 일괄 등록 요청")
public record TermAgreementCreateRequest(
        @Schema(description = "동의한 약관 목록(모두 현재 유효 버전이어야 전체가 기록된다)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<Agreement> agreements
) {

    /** 동의 한 항목 — 조회 응답의 {@code (termType, version)}을 그대로 회신한다. */
    @Schema(description = "동의 항목")
    public record Agreement(
            @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE",
                    requiredMode = Schema.RequiredMode.REQUIRED) TermType termType,
            @Schema(description = "동의한 버전 문자열", example = "2026-08-15",
                    requiredMode = Schema.RequiredMode.REQUIRED) String version
    ) {
    }
}
