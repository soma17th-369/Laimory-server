package com.laimory.server.terms.service;

import static org.mockito.Mockito.verify;

import com.laimory.server.terms.repository.TermAgreementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** batch insert가 문서마다 같은 수락 시각·같은 감사 시각(1회 캡처)을 바인딩하는지 고정. */
@ExtendWith(MockitoExtension.class)
class TermAgreementTransactionServiceTest {

    @Mock
    private TermAgreementRepository termAgreementRepository;

    @Test
    void recordAgreements_bindsSameAcceptedAtAndAuditTimestampForWholeBatch() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-15T20:30:00Z"), ZoneOffset.UTC);
        TermAgreementTransactionService service = new TermAgreementTransactionService(
                termAgreementRepository, clock);
        LocalDateTime acceptedAtKst = LocalDateTime.parse("2026-08-16T05:30:00");
        LocalDateTime auditNow = LocalDateTime.now(clock);

        service.recordAgreements(7L, List.of(11L, 12L), acceptedAtKst);

        verify(termAgreementRepository).insertIfAbsent(7L, 11L, acceptedAtKst, auditNow);
        verify(termAgreementRepository).insertIfAbsent(7L, 12L, acceptedAtKst, auditNow);
    }
}
