package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.LocalDateTime;

/**
 * 현재 유효 약관 문서 한 건. 원문은 응답에 담지 않고 {@code contentUrl}이 가리키는 게시된 정적 page가
 * 소유한다 — 클라이언트는 이 URL을 WebView로 연다(#320). {@code required}는 DB 사본이 아니라
 * {@link TermType} mapping 값이다 — 잘못된 seed가 필수 여부를 조용히 바꾸지 못한다.
 * {@code effectiveAt}은 {@code Asia/Seoul} 벽시계 {@code LocalDateTime}이라 offset 없는 ISO 문자열로
 * 직렬화된다.
 */
@Schema(description = "약관 문서")
public record TermResponse(
        @Schema(description = "약관 종류", example = "TERMS_OF_SERVICE") TermType termType,
        @Schema(description = "버전 문자열 — 동의 등록 시 그대로 회신한다", example = "1.0") String version,
        @Schema(description = "약관 제목", example = "이용약관") String title,
        @Schema(description = "버전별 약관 원문 WebView HTTPS URL",
                example = "https://laimory.app/terms/privacy-policy/1.0",
                format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED) URI contentUrl,
        @Schema(description = "필수 동의 여부") boolean required,
        @Schema(description = "효력 시작 시각(Asia/Seoul 벽시계, offset 없음)",
                example = "2026-09-01T00:00:00") LocalDateTime effectiveAt
) {

    public static TermResponse from(TermDocument document, URI contentUrl) {
        return new TermResponse(document.getTermType(), document.getVersion(), document.getTitle(),
                contentUrl, document.getTermType().required(), document.getEffectiveAt());
    }
}
