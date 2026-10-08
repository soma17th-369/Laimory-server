package com.laimory.server.credit.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.dto.CreditCostsResponse;
import com.laimory.server.credit.dto.CreditResponse;
import com.laimory.server.credit.entity.CreditCost;
import com.laimory.server.credit.entity.SubjectCredit;
import com.laimory.server.credit.repository.CreditCostRepository;
import com.laimory.server.credit.repository.SubjectCreditRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * subject 크레딧 잔액의 단일 관문(#548). 크레딧은 타임라인 전용이 아닌 범용 재화다.
 *
 * <p>행을 만드는 것은 가입 transaction과 rollout backfill뿐이다. 행 부재는 깨진 불변식이라 조회·사전 검사는
 * 기본값으로 가리지 않고 던진다({@code SubjectPreferenceService} 선례).
 *
 * <p>차감은 소비한 결과를 저장하는 transaction 안에서 한다 — 결과가 저장되지 않은 시도는 차감되지 않으므로
 * 환불 경로가 없다. 반대로 결과가 저장됐다면 요청 응답(502 등)이나 작업 종결 여부와 무관하게 차감은 유지된다.
 *
 * <p>소비 비용은 DB {@code credit_costs} 행이 단일 기준이다(#558) — 앱 고지·사전 검사·차감이 매 요청 같은 행을
 * 읽는다. 서버별 캐시를 두면 비용 변경 migration 직후 서버마다 값이 갈리므로 두지 않는다. 비용 행 부재도 깨진
 * 불변식(migration seed 누락)이라 기본값으로 가리지 않고 던진다.
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    /** 가입 시 지급하는 크레딧. 평생 지급량이며 충전 경로는 없다. */
    static final int DEFAULT_CREDITS = 60;

    private final SubjectCreditRepository subjectCreditRepository;
    private final CreditCostRepository creditCostRepository;
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
     * 부수효과 전에 부른다. 통과가 차감 예약은 아니다 — 동시 요청이 함께 통과하면 차감 시점에 0에서 멈춘다.
     */
    public void requireAvailable(UUID subjectId, CreditCostType type) {
        if (findRemaining(subjectId) < findCost(type)) {
            throw new BusinessException(ExceptionType.INSUFFICIENT_CREDIT);
        }
    }

    /**
     * 비용만큼 차감 — 호출자의 결과 저장 transaction에 합류한다(롤백되면 차감도 롤백). 잔액이 비용보다 적으면
     * 0에서 멈추고 오류 없이 넘어간다: 사전 검사를 함께 통과한 동시 요청의 결과를 버리지 않기 위해서다(수용한
     * 무료 생성).
     */
    public void deduct(UUID subjectId, CreditCostType type) {
        subjectCreditRepository.deduct(subjectId, findCost(type));
    }

    /** 계정 삭제(#302)의 잔액 행 제거 — subject mapping 삭제 전에 호출해야 한다(FK RESTRICT). 미존재는 0행(멱등). */
    public void delete(UUID subjectId) {
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
