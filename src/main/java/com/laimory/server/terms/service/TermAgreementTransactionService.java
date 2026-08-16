package com.laimory.server.terms.service;

import com.laimory.server.terms.repository.TermAgreementRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 batch insert의 명시적 transaction 경계 — 검증을 끝낸 문서 ID 목록을 한 DB transaction으로
 * 기록한다(일부만 남는 부분 상태 없음).
 *
 * <p>이미 있는 {@code (user, document)} 행은 native {@code INSERT IGNORE}가 원자적으로 건너뛴다 —
 * 재전송·동시 동일 batch가 unique 예외로 transaction을 rollback-only로 오염시키지 않고 한 이력으로
 * 수렴하며, 기존 수락 시각은 덮어쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TermAgreementTransactionService {

    private final TermAgreementRepository termAgreementRepository;
    private final Clock clock;

    /** {@code acceptedAtKst}는 호출자가 검증과 같은 축에서 캡처한 KST 벽시계다(batch 전체 동일). */
    @Transactional
    public void recordAgreements(Long userId, List<Long> termDocumentIds, LocalDateTime acceptedAtKst) {
        // native insert는 JPA auditing을 우회한다 — 감사 시각은 batch 시작 전에 한 번 캡처해 함께 바인딩.
        LocalDateTime auditNow = LocalDateTime.now(clock);
        for (Long termDocumentId : termDocumentIds) {
            termAgreementRepository.insertIfAbsent(userId, termDocumentId, acceptedAtKst, auditNow);
        }
    }
}
