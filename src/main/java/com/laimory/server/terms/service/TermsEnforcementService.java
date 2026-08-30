package com.laimory.server.terms.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 단계별 필수 약관과 요청별 조건부 약관 동의 강제.
 *
 * <p>판정은 요청 시점 DB 권위(현재 필수 문서 + 동의 existence 한 번)다. "첫 1회"류 판정도 기록 존재가
 * 아니라 해당 현재 약관 버전의 agreement 존재로 한다 — 약관이 개정되면 새 필수 버전 재동의를 요구한다.
 *
 * <p>catalog가 준비되지 않은 stage(seed/activation 누락·mapping 불일치)는 부분 강제 없이 전체를
 * fail-open한다 — Android 조율·seed 오류가 5xx나 전 회원 차단으로 이어지지 않게 하고, 상태는
 * {@link TermCatalogReadiness}의 metric·전이 로그가 경보한다. 조건부 문서 누락은 해당 gate만
 * fail-open하고 다른 필수 stage 판정을 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TermsEnforcementService {

    private final TermCatalogReadiness termCatalogReadiness;
    private final TermAgreementService termAgreementService;

    /** 현재 필수 문서 전부에 동의가 없으면 403({@code -3001})을 던진다. */
    public void requireAgreements(TermStage stage, Long userId) {
        TermCatalogReadiness.StageCatalog catalog = termCatalogReadiness.checkStage(stage);
        if (!catalog.ready()) {
            termCatalogReadiness.recordFailOpen(stage);
            return;
        }
        List<Long> requiredDocumentIds = catalog.currentEnforcedDocuments().stream()
                .map(TermDocumentSummary::termDocumentId)
                .toList();
        if (!termAgreementService.hasAgreedToAll(userId, requiredDocumentIds)) {
            throw new BusinessException(ExceptionType.TERMS_AGREEMENT_REQUIRED);
        }
    }

    /** 현재 조건부 문서에 동의가 없으면 403({@code -3001}), 문서 누락이면 이 gate만 fail-open한다. */
    public void requireConditionalAgreement(TermType termType, Long userId) {
        TermCatalogReadiness.ConditionalTermCatalog catalog =
                termCatalogReadiness.checkConditionalTerm(termType);
        if (!catalog.ready()) {
            termCatalogReadiness.recordConditionalFailOpen(termType);
            return;
        }
        long currentDocumentId = catalog.currentDocument().orElseThrow().termDocumentId();
        if (!termAgreementService.hasAgreedToAll(userId, List.of(currentDocumentId))) {
            throw new BusinessException(ExceptionType.TERMS_AGREEMENT_REQUIRED);
        }
    }
}
