package com.laimory.server.credit.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.dto.CreditCostsResponse;
import com.laimory.server.credit.dto.CreditResponse;
import com.laimory.server.credit.entity.CreditCost;
import com.laimory.server.credit.entity.CreditHistory;
import com.laimory.server.credit.entity.SubjectCredit;
import com.laimory.server.credit.repository.CreditCostRepository;
import com.laimory.server.credit.repository.CreditHistoryRepository;
import com.laimory.server.credit.repository.SubjectCreditRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * subject 크레딧 잔액의 단일 관문(#548). 크레딧은 타임라인 전용이 아닌 범용 재화다.
 *
 * <p>행을 만드는 것은 가입 transaction과 rollout backfill뿐이다. 행 부재는 깨진 불변식이라 조회·사전 검사는
 * 기본값으로 가리지 않고 던진다({@code SubjectPreferenceService} 선례).
 *
 * <p>차감은 작업 요청 시점에 한다(#565) — 잔액이 비용 이상일 때만 조건부로 빼고 사용 1건당 기록
 * ({@code credit_histories}, {@code CHARGED})을 같은 transaction에 남긴다. 결과가 저장되면 기록을 {@code CONSUMED}로
 * 확정하고, 생성이 실패하거나 결과 없이 유실되면 기록의 차감액을 돌려주고 {@code REFUNDED}로 바꾼다. 환불은
 * 기록이 {@code CHARGED}일 때만 일어나므로 같은 작업의 순차 재처리는 한 번만 환불한다.
 *
 * <p>결과도 실패 보고도 없이 끝난 작업(유실)의 감지는 크레딧이 아니라 작업을 소유한 도메인이 맡는다(타임라인은
 * draft task 유실 타이머) — 감지한 쪽이 {@link #refund}를 부른다. 금액·주인은 기록이 권위다.
 *
 * <p>소비 비용은 DB {@code credit_costs} 행이 단일 기준이다(#558) — 앱 고지·사전 검사·차감이 매 요청 같은 행을
 * 읽는다. 서버별 캐시를 두면 비용 변경 migration 직후 서버마다 값이 갈리므로 두지 않는다. 비용 행 부재도 깨진
 * 불변식(migration seed 누락)이라 기본값으로 가리지 않고 던진다.
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    /**
     * 가입 시 지급하는 크레딧. 평생 지급량이며 충전 경로는 없다. 행 생성이 insert-if-absent라 이 값을 바꿔도
     * 이미 행이 있는 subject의 잔액은 바뀌지 않는다 — 60이던 시절의 가입자와 V6 backfill 대상은 60을 받았다.
     */
    static final int DEFAULT_CREDITS = 30;

    private final SubjectCreditRepository subjectCreditRepository;
    private final CreditCostRepository creditCostRepository;
    private final CreditHistoryRepository creditHistoryRepository;
    private final Clock clock;

    /**
     * 가입 transaction 합류용 기본 행 생성. 이미 있으면 건너뛴다(멱등).
     * 기본값을 INSERT에 명시한다 — DB default를 두지 않아 기본값의 권위가 이 상수 하나다.
     */
    public void createDefaultIfAbsent(UUID subjectId) {
        subjectCreditRepository.insertIfAbsent(subjectId.toString(), DEFAULT_CREDITS, LocalDateTime.now(clock));
    }

    /** 내 크레딧 조회 — 순수 읽기다. 행이 없으면 던진다(500 — 운영 신호). */
    public CreditResponse getCredit(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return new CreditResponse(findRemaining(subjectId));
    }

    /** 크레딧 비용 조회(#555) — 사용자와 무관하며 사전 검사·차감과 같은 {@code credit_costs} 행을 읽는다(#558). */
    public CreditCostsResponse getCosts(String applicationVersion) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return new CreditCostsResponse(findCost(CreditCostType.TIMELINE_CREATION));
    }

    /**
     * 크레딧을 소비하는 작업의 사전 검사 — 잔액이 비용보다 적으면 403({@code -1021})으로 거절한다. 호출자는
     * 외부 호출 등 부수효과 전에 부른다. 통과가 차감 예약은 아니다 — 동시 요청이 함께 통과하면 {@link #charge}의
     * 조건부 차감이 넘치는 쪽을 403으로 거절한다.
     */
    public void requireAvailable(UUID subjectId, CreditCostType type) {
        if (findRemaining(subjectId) < findCost(type)) {
            throw new BusinessException(ExceptionType.INSUFFICIENT_CREDIT);
        }
    }

    /**
     * 비용만큼 차감하고 사용 기록({@code CHARGED})을 남긴다 — 호출자의 transaction에 합류한다(롤백되면 둘 다
     * 롤백). 잔액이 비용보다 적으면 403({@code -1021})이다. 기록의 {@code amount}가 이후 환불액이다.
     */
    public void charge(UUID subjectId, CreditCostType type, String referenceId) {
        int cost = findCost(type);
        if (subjectCreditRepository.chargeIfSufficient(subjectId, cost) == 0) {
            throw new BusinessException(ExceptionType.INSUFFICIENT_CREDIT);
        }
        creditHistoryRepository.insertCharged(subjectId.toString(), type.name(), referenceId, cost,
                LocalDateTime.now(clock));
    }

    /**
     * 결과 저장으로 차감을 확정한다({@code CONSUMED}) — 결과 저장 transaction에 합류한다. 기록이 없으면(기록 도입
     * 전에 시작한 작업) 오류 없이 건너뛴다(그 작업은 차감 없이 저장된다).
     */
    public void consume(CreditCostType type, String referenceId) {
        creditHistoryRepository.markConsumed(type, referenceId, CreditHistoryStatus.CONSUMED,
                LocalDateTime.now(clock));
    }

    /**
     * 기록이 {@code CHARGED}일 때만 차감액을 돌려주고 {@code REFUNDED}로 바꾼다(한 transaction). 환불했으면 그
     * subject를, 기록이 없거나(계정 삭제·기록 도입 전 작업) 이미 소비·환불됐으면 비어 있는 값을 돌려준다 —
     * 같은 작업의 순차 재처리가 두 번 환불하지 않는다. 동시에 겹친 환불은 거르지 않는다(수용한 경합).
     */
    @Transactional
    public Optional<UUID> refund(CreditCostType type, String referenceId, CreditRefundReason reason) {
        Optional<CreditHistory> charged = creditHistoryRepository.findByCostTypeAndReferenceId(type, referenceId)
                .filter(history -> history.getStatus() == CreditHistoryStatus.CHARGED);
        if (charged.isEmpty()) {
            return Optional.empty();
        }
        CreditHistory history = charged.get();
        subjectCreditRepository.refund(history.getSubjectId(), history.getAmount());
        creditHistoryRepository.markRefunded(history.getCreditHistoryId(), CreditHistoryStatus.REFUNDED, reason,
                LocalDateTime.now(clock));
        return Optional.of(history.getSubjectId());
    }

    /**
     * 계정 삭제(#302)의 사용 기록·잔액 행 제거 — subject mapping 삭제 전에 호출해야 한다(둘 다 FK RESTRICT).
     * 미존재는 0행(멱등).
     */
    public void delete(UUID subjectId) {
        creditHistoryRepository.deleteBySubjectId(subjectId);
        subjectCreditRepository.deleteBySubjectId(subjectId);
    }

    private int findRemaining(UUID subjectId) {
        return subjectCreditRepository.findBySubjectId(subjectId)
                .map(SubjectCredit::getRemaining)
                .orElseThrow(() -> new IllegalStateException("subject credit row is missing"));
    }

    private int findCost(CreditCostType type) {
        return creditCostRepository.findByType(type)
                .map(CreditCost::getCost)
                .orElseThrow(() -> new IllegalStateException("credit cost row is missing: " + type));
    }
}
