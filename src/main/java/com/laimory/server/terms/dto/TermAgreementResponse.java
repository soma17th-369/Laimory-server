package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermAgreementHistoryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 동의 이력 한 건 — 문서 버전이 불변이므로 "언제 어떤 버전에 동의했는지"를 그대로 재구성한다. 동의 당시
 * 원문은 그 버전 행에 저장된 {@code contentUrl}이 가리키는 page가 재현한다(게시된 버전 URL은 불변이고,
 * 행에 박혀 있으므로 이후 게시 규칙이 바뀌어도 과거 이력이 다른 주소로 흘러가지 않는다).
 * {@code acceptedAt}은 서버가 기록한 {@code Asia/Seoul} 벽시계다(offset 없음).
 * 여섯 field 모두 always-present non-null이므로 전부 required로 문서화한다.
 */
@Schema(description = "약관 동의 이력 항목")
public record TermAgreementResponse(
        @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE",
                requiredMode = Schema.RequiredMode.REQUIRED) TermType termType,
        @Schema(description = "동의한 버전 문자열", example = "1.0",
                requiredMode = Schema.RequiredMode.REQUIRED) String version,
        @Schema(description = "약관 제목", example = "이용약관",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "버전별 약관 원문 WebView HTTPS URL",
                example = "https://www.laimory.app/terms/privacy-policy/1.0",
                format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED) String contentUrl,
        @Schema(description = "효력 시작 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-09-01T00:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime effectiveAt,
        @Schema(description = "서버 수락 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-09-02T09:30:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime acceptedAt
) {

    public static TermAgreementResponse from(TermAgreementHistoryEntry entry) {
        return new TermAgreementResponse(
                entry.document().getTermType(),
                entry.document().getVersion(),
                entry.document().getTitle(),
                entry.document().getContentUrl(),
                entry.document().getEffectiveAt(),
                entry.agreement().getAcceptedAt());
    }
}
