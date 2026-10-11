package com.laimory.server.timeline.service;

import com.laimory.server.timeline.repository.TimelineTaskLossTimerStore;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * draft task 유실 타이머 leaf 서비스(#565). 자신과 1:1인 {@link TimelineTaskLossTimerStore}에만 접근한다.
 *
 * <p>크레딧을 차감한 task는 차감 commit 뒤 타이머를 걸고, 결과 저장·환불로 정상 종결한 경로가 지운다. 결과·실패
 * 보고 없이 PROCESSING TTL이 지나면 타이머가 만료되고 {@link TimelineTaskLossHandler}가 유실 처리한다.
 */
@Service
@RequiredArgsConstructor
public class TimelineTaskLossTimerService {

    /**
     * 유실 타이머 = PROCESSING TTL. AI 처리는 운영 실측 2분 이내라(3개월간 2분 초과 없음) 정상 task는 결과 저장·
     * FAILED callback과 타이머 삭제를 만료보다 충분히 먼저 끝낸다 — 별도 여유를 두지 않는다.
     */
    private static final Duration TIMER_TTL = TimelineTaskService.PROCESSING_TTL;

    private final TimelineTaskLossTimerStore timelineTaskLossTimerStore;

    public void start(String taskId) {
        timelineTaskLossTimerStore.start(taskId, TIMER_TTL);
    }

    public void cancel(String taskId) {
        timelineTaskLossTimerStore.cancel(taskId);
    }

    /** 만료 알림을 함께 받은 서버들 중 한 대만 유실 처리하도록 잠금을 잡는다. */
    public boolean tryLock(String taskId) {
        return timelineTaskLossTimerStore.tryLock(taskId);
    }

    /** 만료 알림 구독 패턴의 논리 키 prefix. */
    public String timerKeyPrefix() {
        return TimelineTaskLossTimerStore.TIMER_KEY_PREFIX;
    }

    /** 만료된 논리 키가 유실 타이머 키와 정확히 일치하면 taskId를 돌려준다. */
    public Optional<String> taskIdOf(String logicalKey) {
        return TimelineTaskLossTimerStore.taskIdOf(logicalKey);
    }
}
