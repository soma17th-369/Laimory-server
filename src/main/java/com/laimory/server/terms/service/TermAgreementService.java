package com.laimory.server.terms.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermAgreementRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 약관 동의 일괄 등록·이력 조회·필수 동의 판정.
 *
 * <p>동의 등록은 all-or-nothing이다: 제출한 모든 {@code (termType, version)}이 지금 이 순간의 현재
 * 버전이어야 기록한다. 하나라도 존재하지 않거나 개정으로 현재 버전이 바뀌었으면 아무것도 기록하지 않고
 * 409({@code -3002})로 거절해 앱이 현재 약관을 다시 조회하게 한다. 수락 시각은 클라이언트 입력이 아니라
 * 서버가 한 번 캡처한 instant의 KST 벽시계이며 batch 전체에 같은 값을 쓴다 — 유효성 판정과 수락 시각이
 * 같은 시각 축을 공유한다.
 */
@Service
@RequiredArgsConstructor
public class TermAgreementService {

    private final TermDocumentService termDocumentService;
    private final TermAgreementTransactionService termAgreementTransactionService;
    private final TermAgreementRepository termAgreementRepository;
    private final Clock clock;

    /**
     * 동의 일괄 등록(멱등) — 같은 회원이 같은 버전을 다시 보내도 성공하며 기존 수락 시각을 덮어쓰지
     * 않는다. 요청 안 동일 {@code (termType, version)} 중복은 400이다.
     */
    public void agreeToTerms(String applicationVersion, Long userId, List<TermAgreementCommand> agreements) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        validateShape(agreements);

        LocalDateTime nowKst = TermTimes.kstWallClock(clock.instant());
        // 버전 검증에는 원문이 필요 없다 — content 제외 요약만 조회한다.
        Map<TermType, TermDocumentSummary> currentByType = termDocumentService.findCurrentSummaries(
                        agreements.stream().map(TermAgreementCommand::termType).collect(Collectors.toSet()), nowKst)
                .stream()
                .collect(Collectors.toMap(TermDocumentSummary::termType, Function.identity()));

        List<Long> documentIds = agreements.stream()
                .map(agreement -> requireCurrentDocument(currentByType, agreement))
                .map(TermDocumentSummary::termDocumentId)
                .toList();

        termAgreementTransactionService.recordAgreements(userId, documentIds, nowKst);
    }

    /** 회원에게 남아 있는 전체 동의 이력({@code acceptedAt DESC}, 안정 tie-breaker). 없으면 빈 목록이다. */
    public List<TermAgreementHistoryEntry> getHistory(String applicationVersion, Long userId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return termAgreementRepository.findHistoryByUserId(userId);
    }

    /** 주어진 문서 전부에 동의했는지 — enforcement용 단일 existence query. 빈 목록은 true다. */
    /**
     * 계정 삭제(#302)의 owner 동의 이력 전량 제거 — 완전 소거 확정(계획 §3.2)이라 탈퇴 회원의 증적은
     * 보존하지 않는다. 미존재는 0행(멱등)이며 호출자 transaction에 합류한다.
     */
    public void deleteAllByUserId(long userId) {
        termAgreementRepository.deleteAllByUserId(userId);
    }

    public boolean hasAgreedToAll(Long userId, List<Long> termDocumentIds) {
        if (termDocumentIds.isEmpty()) {
            return true;
        }
        return termAgreementRepository.countByUserIdAndTermDocumentIdIn(userId, termDocumentIds)
                == termDocumentIds.size();
    }

    private static void validateShape(List<TermAgreementCommand> agreements) {
        if (agreements == null || agreements.isEmpty()) {
            throw new IllegalArgumentException("agreements must not be null or empty");
        }
        Set<TermAgreementCommand> seen = new HashSet<>();
        for (TermAgreementCommand agreement : agreements) {
            if (agreement == null || agreement.termType() == null) {
                throw new IllegalArgumentException("each agreement requires termType");
            }
            if (agreement.version() == null || agreement.version().isBlank()) {
                throw new IllegalArgumentException("each agreement requires version");
            }
            if (!seen.add(agreement)) {
                throw new IllegalArgumentException("duplicate (termType, version) in agreements");
            }
        }
    }

    /** 제출 항목이 현재 버전과 정확히 일치해야 한다 — 미존재·과거/미래 버전은 같은 409로 수렴한다. */
    private static TermDocumentSummary requireCurrentDocument(Map<TermType, TermDocumentSummary> currentByType,
                                                              TermAgreementCommand agreement) {
        TermDocumentSummary current = currentByType.get(agreement.termType());
        if (current == null || !current.version().equals(agreement.version())) {
            throw new BusinessException(ExceptionType.STALE_TERM_VERSION);
        }
        return current;
    }
}
