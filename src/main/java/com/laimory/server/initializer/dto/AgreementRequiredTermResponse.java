package com.laimory.server.initializer.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocumentId;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 동의가 필요한 약관 한 건(#434). 앱은 {@code (termType, version)}을 동의 등록에 그대로 회신한다 —
 * 공개 약관 조회 응답과 같은 회신 규약이다. 제목·원문 URL은 담지 않는다(공개 조회가 소유).
 */
@Schema(description = "동의가 필요한 약관")
public record AgreementRequiredTermResponse(
        @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE",
                requiredMode = Schema.RequiredMode.REQUIRED) TermType termType,
        @Schema(description = "현재 버전 문자열 — 동의 등록 시 그대로 회신한다", example = "1.0",
                pattern = TermDocumentId.VERSION_PATTERN_TEXT, maxLength = TermDocumentId.VERSION_MAX_LENGTH,
                requiredMode = Schema.RequiredMode.REQUIRED) String version
) {
}
