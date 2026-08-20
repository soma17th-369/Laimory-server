package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermAgreementHistoryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.LocalDateTime;

/**
 * 동의 이력 한 건 — 문서 버전이 불변이므로 "언제 어떤 버전에 동의했는지"를 그대로 재구성한다. 동의 당시
 * 원문은 그 버전의 {@code contentUrl}이 가리키는 게시된 page가 재현한다(게시된 버전 URL은 불변이다).
 * {@code acceptedAt}은 서버가 기록한 {@code Asia/Seoul} 벽시계다(offset 없음).
 */
@Schema(description = "약관 동의 이력 항목")
public record TermAgreementResponse(
        @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE") TermType termType,
        @Schema(description = "동의한 버전 문자열", example = "1.0") String version,
        @Schema(description = "약관 제목", example = "이용약관") String title,
        @Schema(description = "버전별 약관 원문 WebView HTTPS URL",
                example = "https://laimory.app/terms/privacy-policy/1.0",
                format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED) URI contentUrl,
        @Schema(description = "필수 동의 여부") boolean required,
        @Schema(description = "효력 시작 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-09-01T00:00:00") LocalDateTime effectiveAt,
        @Schema(description = "서버 수락 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-09-02T09:30:00") LocalDateTime acceptedAt
) {

    public static TermAgreementResponse from(TermAgreementHistoryEntry entry, URI contentUrl) {
        return new TermAgreementResponse(
                entry.document().getTermType(),
                entry.document().getVersion(),
                entry.document().getTitle(),
                contentUrl,
                entry.document().getTermType().required(),
                entry.document().getEffectiveAt(),
                entry.agreement().getAcceptedAt());
    }
}
