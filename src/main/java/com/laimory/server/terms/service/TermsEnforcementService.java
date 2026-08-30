package com.laimory.server.terms.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 초기 동의 대상 필수 약관의 동의 강제.
 *
 * <p>판정은 요청 시점 DB 권위(현재 필수 문서 + 동의 existence 한 번)다. "첫 1회"류 판정도 기록 존재가
 * 아니라 해당 현재 약관 버전의 agreement 존재로 한다 — 약관이 개정되면 새 필수 버전 재동의를 요구한다.
 *
 * <p>catalog가 준비되지 않으면(seed/activation 누락·mapping 불일치) 부분 강제 없이 전체를
 * fail-open한다 — Android 조율·seed 오류가 5xx나 전 회원 차단으로 이어지지 않게 하고, 상태는
 * {@link TermCatalogReadiness}의 metric·전이 로그가 경보한다.
 */
@Service
@RequiredArgsConstructor
public class TermsEnforcementService {

    private final TermCatalogReadiness termCatalogReadiness;
    private final TermAgreementService termAgreementService;

    /** 현재 필수 문서 전부에 동의가 없으면 403({@code -3001})을 던진다. */
    public void requireAgreements(Long userId) {
        TermCatalogReadiness.Catalog catalog = termCatalogReadiness.check();
        if (!catalog.ready()) {
            termCatalogReadiness.recordFailOpen();
            return;
        }
        List<Long> requiredDocumentIds = catalog.currentEnforcedDocuments().stream()
                .map(TermDocumentSummary::termDocumentId)
                .toList();
        if (!termAgreementService.hasAgreedToAll(userId, requiredDocumentIds)) {
            throw new BusinessException(ExceptionType.TERMS_AGREEMENT_REQUIRED);
        }
    }
}
