package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 하루 기록 저장 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>순서가 load-bearing이다: 사전 검증(404 은닉·SAVED 409) → 별도 트랜잭션에서 조건부 UPDATE로
 * {@code DRAFT→SAVED} 커밋({@link TimelineSaveTransactionService}) → <b>커밋 뒤</b> User Memory 갱신 접수
 * 요청 → 200. 트랜잭션 안에서 접수를 깨우면 롤백된 저장이 갱신을 유발할 수 있다.
 *
 * <p><b>200은 저장 완료다.</b> 뒤따르는 갱신 접수는 best-effort이며 실패해도 저장을 되돌리지 않는다 —
 * User Memory는 다음 타임라인 품질을 높이는 보조 데이터이지 저장의 일부가 아니다.
 * 요청 스레드는 AI를 호출하지 않는다.
 */
@Slf4j
@Service
public class TimelineSaveService {

    private final DailyRecordService dailyRecordService;
    private final TimelineSaveTransactionService timelineSaveTransactionService;
    private final UserMemoryUpdateWorker userMemoryUpdateWorker;

    public TimelineSaveService(
            DailyRecordService dailyRecordService,
            TimelineSaveTransactionService timelineSaveTransactionService,
            UserMemoryUpdateWorker userMemoryUpdateWorker) {
        this.dailyRecordService = dailyRecordService;
        this.timelineSaveTransactionService = timelineSaveTransactionService;
        this.userMemoryUpdateWorker = userMemoryUpdateWorker;
    }

    /**
     * 인증 사용자의 해당 날짜 DRAFT 하루 기록을 SAVED로 확정한다.
     *
     * @throws BusinessException 없음·비소유 404 {@code -404}, 이미 SAVED 409 {@code -1003}
     */
    public void save(String applicationVersion, long userId, LocalDate recordDate) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findByUserIdAndRecordDate(userId, recordDate)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }

        Long dailyRecordId = record.getDailyRecordId();
        timelineSaveTransactionService.save(userId, dailyRecordId);
        requestUserMemoryUpdate(userId, dailyRecordId);
    }

    /**
     * User Memory 갱신 접수를 async로 깨우고 곧바로 돌아온다. 경합이 없으면 이것으로 끝이고 Redis 큐는
     * 아예 쓰이지 않는다 — 사용자 guard를 못 잡은 경우에만 worker가 그 작업을 큐에 남기고, 하루 1회
     * 배치가 그것만 처리한다. guard 획득 실패가 곧 "이 사용자의 갱신이 진행 중"이라는 판정이라, 실패를
     * 기록할 지점이 거기 하나로 모인다.
     *
     * <p>실패해도 저장 응답을 깨지 않는다 — 그 날치 User Memory 반영만 누락되고 사용자의 저장은 이미
     * 커밋됐다. 누락 빈도를 판단할 수 있도록 식별자와 함께 남긴다.
     */
    private void requestUserMemoryUpdate(long userId, Long dailyRecordId) {
        try {
            userMemoryUpdateWorker.dispatchNow(new UserMemoryUpdatePending(userId, dailyRecordId));
        } catch (RuntimeException e) {
            // async 실행기 포화 등으로 거절되면 그 날치 갱신은 누락된다(저장은 이미 완료).
            log.error("User Memory 갱신 접수 트리거 실패(저장은 완료): userId={} dailyRecordId={}",
                    userId, dailyRecordId, e);
        }
    }
}
