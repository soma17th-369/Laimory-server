package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * timeline draft 작업 상태 leaf 서비스. 자신과 1:1인 TimelineTaskStore에만 접근한다.
 * 처리중(PROCESSING)은 1시간, 종결 상태(SUCCESS/FAILED)는 24시간 TTL로 보관한다.
 */
@Service
@RequiredArgsConstructor
public class TimelineTaskService {

    private static final Duration PROCESSING_TTL = Duration.ofHours(1);
    private static final Duration TERMINAL_TTL = Duration.ofHours(24);
    // terminal TTL(24h)보다 길게 유지해 카운터가 task보다 먼저 만료되지 않게 한다.
    private static final Duration TOKEN_USES_TTL = Duration.ofHours(25);

    private final TimelineTaskStore timelineTaskStore;

    public void createProcessing(String taskId, LocalDate recordDate, LocalDateTime recordAt, String recordTimezone,
                                 String callbackTokenHash) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.processing(recordDate, recordAt, recordTimezone, callbackTokenHash), PROCESSING_TTL);
    }

    public void markSuccess(String taskId, LocalDate recordDate, String callbackTokenHash) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.success(recordDate, callbackTokenHash), TERMINAL_TTL);
    }

    /**
     * task를 FAILED로 종결한다. {@code failureCode}는 task 실패 분류({@link ErrorCode#TASK_FAILURE_CODES})만
     * 허용한다 — raw 문자열을 받지 않아, 내부 예외 메시지가 폴링 {@code body.error}로 유출되는 경로를
     * 시그니처에서 차단한다(상세는 호출부가 로그로만 남긴다).
     */
    public void markFailed(String taskId, LocalDate recordDate, ErrorCode failureCode, String callbackTokenHash) {
        if (!ErrorCode.TASK_FAILURE_CODES.contains(failureCode)) {
            throw new IllegalStateException("task 실패 분류 코드가 아닙니다: " + failureCode);
        }
        timelineTaskStore.save(taskId,
                TimelineDraftTask.failed(recordDate, failureCode.name(), callbackTokenHash), TERMINAL_TTL);
    }

    public Optional<TimelineDraftTask> find(String taskId) {
        return timelineTaskStore.find(taskId);
    }

    /**
     * 콜백 토큰을 원자적으로 소비한다. 반환 1 = 이 요청이 유일한 승자(처리 계속), 그 외 = 이미 소비됨.
     * 카운터는 TTL로만 소멸시킨다 — terminal 이후 삭제하면 task 해시가 남아 있는 동안 replay 창이 다시 열린다.
     */
    public long consumeCallbackToken(String taskId) {
        return timelineTaskStore.incrementCallbackTokenUses(taskId, TOKEN_USES_TTL);
    }
}
