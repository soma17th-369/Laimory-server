package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 현재 유효 약관 문서 한 건. 원문은 응답에 담지 않고 {@code contentUrl}이 가리키는 게시된 page가
 * 소유한다 — 클라이언트는 이 URL을 WebView로 연다(#320).
 * {@code effectiveAt}은 {@code Asia/Seoul} 벽시계 {@code LocalDateTime}이라 offset 없는 ISO 문자열로
 * 직렬화된다. 다섯 field 모두 always-present non-null이므로 전부 required로 문서화한다.
 */
@Schema(description = "약관 문서")
public record TermResponse(
        @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE",
                requiredMode = Schema.RequiredMode.REQUIRED) TermType termType,
        @Schema(description = "버전 문자열 — 동의 등록 시 그대로 회신한다", example = "1.0",
                requiredMode = Schema.RequiredMode.REQUIRED) String version,
        @Schema(description = "약관 제목", example = "이용약관",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "버전별 약관 원문 WebView HTTPS URL",
                example = "https://www.laimory.app/terms/privacy-policy/1.0",
                format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED) String contentUrl,
        @Schema(description = "효력 시작 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-09-01T00:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime effectiveAt
) {

    public static TermResponse from(TermDocument document) {
        return new TermResponse(document.getTermType(), document.getVersion(), document.getTitle(),
                document.getContentUrl(), document.getEffectiveAt());
    }
}
