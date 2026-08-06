package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 하루 기록 저장 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>순서가 load-bearing이다: 사전 검증(404 은닉·SAVED 409) → 별도 트랜잭션에서 조건부 UPDATE로
 * {@code DRAFT→SAVED} 커밋({@link TimelineSaveTransactionService}) → <b>커밋 뒤</b> User Memory 갱신 대기
 * 등록 → 200. 트랜잭션 안에서 대기 등록을 하면 롤백된 저장이 갱신을 유발할 수 있다.
 *
 * <p><b>200은 저장 완료다.</b> 대기 등록과 그 뒤의 AI 접수는 전부 best-effort이며 실패해도 저장을
 * 되돌리지 않는다 — User Memory는 다음 타임라인 품질을 높이는 보조 데이터이지 저장의 일부가 아니다.
 * 요청 스레드는 AI를 호출하지 않는다. 실제 접수는 {@link UserMemoryUpdateWorker}가 사용자 guard를 잡은
 * 뒤 수행하므로, 다른 날짜의 갱신이 진행 중이면 이 요청은 즉시 200이고 갱신만 뒤로 밀린다.
 */
@Slf4j
@Service
public class TimelineSaveService {

    private final DailyRecordService dailyRecordService;
    private final TimelineSaveTransactionService timelineSaveTransactionService;
    private final UserMemoryUpdatePendingStore pendingStore;
    private final Clock clock;
    private final Duration updateDeadline;

    public TimelineSaveService(
            DailyRecordService dailyRecordService,
            TimelineSaveTransactionService timelineSaveTransactionService,
            UserMemoryUpdatePendingStore pendingStore,
            Clock clock,
            @Value("${app.user-memory.update.deadline:5m}") Duration updateDeadline) {
        this.dailyRecordService = dailyRecordService;
        this.timelineSaveTransactionService = timelineSaveTransactionService;
        this.pendingStore = pendingStore;
        this.clock = clock;
        this.updateDeadline = updateDeadline;
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
        enqueueUserMemoryUpdate(userId, dailyRecordId);
    }

    /**
     * 갱신 대기 등록. 실패해도 저장 응답을 깨지 않는다 — 그 날치 User Memory 반영만 누락되고 사용자의
     * 저장은 이미 커밋됐다. 누락 빈도를 판단할 수 있도록 식별자와 함께 남긴다.
     */
    private void enqueueUserMemoryUpdate(long userId, Long dailyRecordId) {
        Instant now = clock.instant();
        try {
            pendingStore.enqueue(
                    new UserMemoryUpdatePending(userId, dailyRecordId, now.plus(updateDeadline)), now);
        } catch (RuntimeException e) {
            log.error("User Memory 갱신 대기 등록 실패(저장은 완료): userId={} dailyRecordId={}",
                    userId, dailyRecordId, e);
        }
    }
}
