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
 * {@code DRAFT→SAVED} 커밋({@link TimelineSaveTransactionService}) → <b>커밋 뒤</b> User Memory 갱신 대기
 * 등록 → 200. 트랜잭션 안에서 등록하면 롤백된 저장이 갱신을 유발할 수 있다.
 *
 * <p><b>200은 저장 완료다.</b> 뒤따르는 갱신 등록은 best-effort이며 실패해도 저장을 되돌리지 않는다 —
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
     * 그 하루를 User Memory 갱신 대기 큐에 넣는다. <b>여기서 AI를 부르지 않는다</b> — 접수는 하루 1회
     * 배치가 사용자별로 묶어 하고, 이 큐가 그 유일한 입력이다. 접수 성공이 반영 성공이 아니라(AI 계약이
     * 202 뒤 결과 콜백), 큐를 거치지 않고 보낸 날은 결과가 끝내 오지 않을 때 재시도할 근거가 남지 않는다.
     *
     * <p>Redis 쓰기 한 번이라 요청 스레드에서 그대로 돌린다 — async로 넘기면 실행기 포화 시 그 하루가
     * 유실될 뿐이다.
     *
     * <p>실패해도 저장 응답을 깨지 않는다 — 그 날치 User Memory 반영만 누락되고 사용자의 저장은 이미
     * 커밋됐다. 누락 빈도를 판단할 수 있도록 식별자와 함께 남긴다.
     */
    private void requestUserMemoryUpdate(long userId, Long dailyRecordId) {
        try {
            userMemoryUpdateWorker.enqueue(new UserMemoryUpdatePending(userId, dailyRecordId));
        } catch (RuntimeException e) {
            // Redis 장애 등으로 큐에 못 넣으면 그 날치 갱신은 누락된다(저장은 이미 완료).
            log.error("User Memory 갱신 대기 등록 실패(저장은 완료): userId={} dailyRecordId={}",
                    userId, dailyRecordId, e);
        }
    }
}
