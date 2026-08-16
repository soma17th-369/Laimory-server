package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.EmotionType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루 기록 저장의 DB 트랜잭션 경계 전담 빈. 오케스트레이터({@link TimelineSaveService})가 Spring
 * 프록시를 통해 호출한다 — 오케스트레이터 안에 {@code @Transactional} 메서드를 두면 self-invocation으로
 * 트랜잭션이 조용히 무효화되므로 분리한다({@link TimelineDeletionTransactionService}와 같은 형태).
 *
 * <p><b>User Memory는 여기서 건드리지 않는다.</b> 전이와 memory 교체는 서로 다른 API가 담당하는 서로
 * 다른 트랜잭션이다 — 이 경계는 사용자의 저장을 즉시 확정하고, memory 교체는 10초+ 뒤 AI가 결과를
 * 들고 왔을 때 별도 endpoint가 수행한다. 그래서 저장이 AI의 성패에 묶이지 않는다.
 *
 * <p>전이는 조건부 UPDATE({@code WHERE status='DRAFT'})의 영향 행 수로 판정한다 — 이것이 이 흐름의
 * <b>유일한 직렬화 지점</b>이다. 사전 검증을 통과한 요청 둘이 겹쳐도 하나만 1을 받고 나머지는 부수효과
 * 없이 롤백된다. 별도 lock·Redis guard는 두지 않는다(사용자 단위 guard는 memory 갱신 쪽 관심사다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineSaveTransactionService {

    private final DailyRecordService dailyRecordService;

    /**
     * 소유 DRAFT record를 요청 감정과 함께 SAVED로 옮긴다. 감정과 상태는 조건부 UPDATE 하나가 유일한
     * write 지점이라 항상 함께 커밋된다(사전 조회 entity를 수정하지 않는다). 반환 시점에 전이는 커밋됐고,
     * 이후의 User Memory 갱신 요청은 실패해도 이 결과를 되돌리지 않는다.
     *
     * @throws BusinessException 전이 실패 — 이미 SAVED면 409 {@code -1003},
     *                           record가 사라졌거나 비소유면 404 {@code -404}
     */
    @Transactional
    public void save(UUID subjectId, Long dailyRecordId, EmotionType emotionType) {
        if (dailyRecordService.markSaved(dailyRecordId, subjectId, emotionType) == 0) {
            throw new BusinessException(classifyTransitionFailure(subjectId, dailyRecordId));
        }
        log.info("하루 기록 저장 commit: dailyRecordId={}", dailyRecordId);
    }

    /**
     * 조건부 UPDATE가 0행일 때의 원인을 분류한다. 행이 남아 있으면 그 사이 SAVED가 됐다는 뜻이고
     * (동시 저장 경합·응답 유실 후 재시도), 없거나 비소유면 삭제됐거나 애초에 남의 기록이다 —
     * 후자는 존재를 노출하지 않는 기존 404 계약으로 수렴시킨다.
     */
    private ExceptionType classifyTransitionFailure(UUID subjectId, Long dailyRecordId) {
        return dailyRecordService.findByDailyRecordIdAndSubjectId(dailyRecordId, subjectId)
                .map(record -> ExceptionType.DAILY_RECORD_ALREADY_SAVED)
                .orElse(ExceptionType.DAILY_RECORD_NOT_FOUND);
    }
}
