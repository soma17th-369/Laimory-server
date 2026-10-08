package com.laimory.server.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.entity.CreditCost;
import com.laimory.server.credit.entity.SubjectCredit;
import com.laimory.server.credit.repository.CreditCostRepository;
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
 * 크레딧 leaf 검증 — 가입 기본값 60, 잔액 0의 사전 거절(-1021), 비용을 credit_costs 행에서 읽는 것(#558),
 * 잔액·비용 행 부재를 기본값으로 가리지 않는 계약을 고정한다.
 * 조건부 차감·삭제의 DB 의미는 {@code CreditPersistenceIntegrationTest}가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CreditServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(91L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T05:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 10, 4, 14, 0);

    @Mock
    private SubjectCreditRepository subjectCreditRepository;

    @Mock
    private CreditCostRepository creditCostRepository;

    private CreditService service() {
        return new CreditService(subjectCreditRepository, creditCostRepository, CLOCK);
    }

    private static SubjectCredit credit(int remaining) {
        SubjectCredit credit = new SubjectCredit() {
        };
        ReflectionTestUtils.setField(credit, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(credit, "remaining", remaining);
        return credit;
    }

    private void givenTimelineCreationCost(int cost) {
        CreditCost row = new CreditCost() {
        };
        ReflectionTestUtils.setField(row, "type", CreditCostType.TIMELINE_CREATION);
        ReflectionTestUtils.setField(row, "cost", cost);
        when(creditCostRepository.findByType(CreditCostType.TIMELINE_CREATION)).thenReturn(Optional.of(row));
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
    void getCostsReportsTimelineCreationCostFromCostRow() {
        // 앱 고지 값은 사전 검사·차감과 같은 비용 행에서 나와야 한다 — 따로 적으면 고지와 실제 차감이 어긋난다.
        givenTimelineCreationCost(3);

        assertThat(service().getCosts("v1").timelineCreation()).isEqualTo(3);
    }

    @Test
    void requireAvailableRejectsZeroCreditsWithInsufficientCredit() {
        givenTimelineCreationCost(1);
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(0)));

        assertThatThrownBy(() -> service().requireAvailable(SUBJECT_ID, CreditCostType.TIMELINE_CREATION))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.INSUFFICIENT_CREDIT);
                    assertThat(ex.getErrorCode()).isEqualTo(-1021);
                });
    }

    @Test
    void requireAvailableRejectsWhenRemainingIsOneBelowCost() {
        // 비용을 1이 아닌 값으로 두어 "잔액 0만 거절"이 아니라 "잔액이 비용보다 적으면 거절"임을 검증한다.
        givenTimelineCreationCost(3);
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(2)));

        assertThatThrownBy(() -> service().requireAvailable(SUBJECT_ID, CreditCostType.TIMELINE_CREATION))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.INSUFFICIENT_CREDIT));
    }

    @Test
    void requireAvailablePassesWhenRemainingEqualsCost() {
        givenTimelineCreationCost(3);
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(3)));

        assertThatCode(() -> service().requireAvailable(SUBJECT_ID, CreditCostType.TIMELINE_CREATION))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAvailableFailsLoudlyWhenRowIsMissing() {
        // 행 부재를 잔액 부족(-1021)으로 바꿔 말하지 않는다 — 클라에 "크레딧 없음"이라 거짓 안내하게 된다.
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireAvailable(SUBJECT_ID, CreditCostType.TIMELINE_CREATION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireAvailableFailsLoudlyWhenCostRowIsMissing() {
        // 비용 행 부재는 migration seed 누락이다 — 0(무료)이나 임의 기본값으로 가리면 조용히 잘못 차감한다.
        when(subjectCreditRepository.findBySubjectId(SUBJECT_ID)).thenReturn(Optional.of(credit(5)));
        when(creditCostRepository.findByType(CreditCostType.TIMELINE_CREATION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireAvailable(SUBJECT_ID, CreditCostType.TIMELINE_CREATION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deductSubtractsCostReadFromCostRow() {
        givenTimelineCreationCost(3);

        service().deduct(SUBJECT_ID, CreditCostType.TIMELINE_CREATION);

        verify(subjectCreditRepository).deduct(SUBJECT_ID, 3);
    }
}
