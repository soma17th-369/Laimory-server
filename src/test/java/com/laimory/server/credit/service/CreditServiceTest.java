package com.laimory.server.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.entity.SubjectCredit;
import com.laimory.server.credit.repository.SubjectCreditRepository;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 크레딧 leaf 검증 — 가입 기본값 60, 잔액 0의 사전 거절(-1021), 행 부재를 기본값으로 가리지 않는 계약을 고정한다.
 * 조건부 차감·삭제의 DB 의미는 {@code CreditPersistenceIntegrationTest}가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CreditServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(91L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T05:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 10, 4, 14, 0);

    @Mock
    private SubjectCreditRepository subjectCreditRepository;

    private CreditService service() {
        return new CreditService(subjectCreditRepository, CLOCK);
    }

    private static SubjectCredit credit(int remaining) {
        SubjectCredit credit = new SubjectCredit() {
        };
        ReflectionTestUtils.setField(credit, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(credit, "remaining", remaining);
        return credit;
    }

    @Test
    void createDefaultGrantsSixtyCreditsWithKstAuditTime() {
        service().createDefaultIfAbsent(SUBJECT_ID);

        verify(subjectCreditRepository).insertIfAbsent(SUBJECT_ID.toString(), 60, NOW_KST);
    }

    @Test
    void getCreditReturnsStoredRemainingCredits() {
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(59)));

        assertThat(service().getCredit("v1", SUBJECT_ID).remainingCredits()).isEqualTo(59);
    }

    @Test
    void getCreditFailsLoudlyWhenRowIsMissing() {
        // 행 부재는 깨진 불변식이다 — 60이나 0으로 가리면 가입·backfill 누락이 조용히 숨는다.
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getCredit("v1", SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireAvailableRejectsZeroCreditsWithInsufficientCredit() {
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(0)));

        assertThatThrownBy(() -> service().requireAvailable(SUBJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.INSUFFICIENT_CREDIT);
                    assertThat(ex.getErrorCode()).isEqualTo(-1021);
                });
    }

    @Test
    void requireAvailablePassesWhenOneCreditRemains() {
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(1)));

        assertThatCode(() -> service().requireAvailable(SUBJECT_ID)).doesNotThrowAnyException();
    }

    @Test
    void requireAvailableFailsLoudlyWhenRowIsMissing() {
        // 행 부재를 잔액 부족(-1021)으로 바꿔 말하지 않는다 — 클라에 "크레딧 없음"이라 거짓 안내하게 된다.
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireAvailable(SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
