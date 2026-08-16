package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermAgreementHistoryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 동의 이력 한 건 — 문서 버전이 불변이므로 "언제 어떤 내용에 동의했는지"를 그대로 재구성한다.
 * {@code acceptedAt}은 서버가 기록한 {@code Asia/Seoul} 벽시계다(offset 없음).
 */
@Schema(description = "약관 동의 이력 항목")
public record TermAgreementResponse(
        @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE") TermType termType,
        @Schema(description = "동의한 버전 문자열", example = "2026-08-15") String version,
        @Schema(description = "약관 제목", example = "이용약관") String title,
        @Schema(description = "동의 당시 약관 원문 전체(불변 버전)") String content,
        @Schema(description = "필수 동의 여부") boolean required,
        @Schema(description = "효력 시작 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-08-15T00:00:00") LocalDateTime effectiveAt,
        @Schema(description = "서버 수락 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-08-16T09:30:00") LocalDateTime acceptedAt
) {

    public static TermAgreementResponse from(TermAgreementHistoryEntry entry) {
        return new TermAgreementResponse(
                entry.document().getTermType(),
                entry.document().getVersion(),
                entry.document().getTitle(),
                entry.document().getContent(),
                entry.document().getTermType().required(),
                entry.document().getEffectiveAt(),
                entry.agreement().getAcceptedAt());
    }
}
