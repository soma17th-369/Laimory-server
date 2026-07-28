package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * timeline draft 작업 상태 leaf 서비스. 자신과 1:1인 TimelineTaskStore에만 접근한다.
 * 처리중(PROCESSING)은 2분, 종결 상태(SUCCESS/FAILED)는 24시간 TTL로 보관한다.
 * PROCESSING 만료는 Redis key 소멸이지 FAILED 전이가 아니다 — callback 없이 만료된 task의
 * 이후 폴링·콜백은 404(-1001)로 수렴하며, scheduler가 FAILED로 복구하지 않는다.
 *
 * <p>callback token은 hash 검증 직후 task별 Redis marker로 원자 소비한다. marker는 terminal task보다
 * 한 시간 긴 25시간 동안 유지하며 소비 뒤 후속 처리가 실패해도 삭제하거나 환불하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TimelineTaskService {

    static final Set<ExceptionType> TASK_FAILURE_TYPES = Collections.unmodifiableSet(EnumSet.of(
            ExceptionType.AI_REPORTED_FAILURE,
            ExceptionType.AI_DISPATCH_FAILED,
            ExceptionType.DRAFT_TASK_FAILURE_FALLBACK));
    private static final Map<Integer, ExceptionType> TASK_FAILURE_TYPES_BY_CODE = TASK_FAILURE_TYPES.stream()
            .collect(Collectors.toUnmodifiableMap(ExceptionType::code, Function.identity()));

    // AI 접수는 202 즉시 반환, 정상 inference·callback은 2분 내 종료가 운영 목표 — 사용자에게
    // 무기한 PROCESSING을 노출하지 않기 위해 callback 없는 task는 이 TTL이 회수한다.
    static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
    private static final Duration TERMINAL_TTL = Duration.ofHours(24);
    static final Duration CALLBACK_TOKEN_USE_TTL = Duration.ofHours(25);

    private final TimelineTaskStore timelineTaskStore;
    private final TimelineMetrics timelineMetrics;

    /**
     * dailyRecordId는 선생성된 DailyRecord의 ID다 — 세 상태 모두 보존되며 폴링·콜백 전이의 기준이다.
     * processingStartedAt은 폴링의 AI 작업 대기 경과 시간 기준(PROCESSING 전용 — terminal은 보존하지 않음).
     * userId는 task owner다 — 세 상태 전이 모두 필수로 받아 보존한다(폴링 소유권 대조·콜백 전이 기준).
     */
    public void createProcessing(String taskId, long userId, long dailyRecordId,
                                 TimelineDraftTask.TimelineWindow timelineWindow,
                                 String callbackTokenHash, Instant processingStartedAt) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.processing(userId, dailyRecordId, timelineWindow,
                        callbackTokenHash, processingStartedAt),
                PROCESSING_TTL);
        timelineMetrics.recordDraftCreated();
    }

    public void markSuccess(String taskId, long userId, long dailyRecordId, String callbackTokenHash) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.success(userId, dailyRecordId, callbackTokenHash), TERMINAL_TTL);
        timelineMetrics.recordTerminalSuccess();
    }

    /**
     * task를 FAILED로 종결한다. {@code failureType}은 task 실패 분류 세 타입만
     * 허용한다 — raw 문자열을 받지 않아, 내부 예외 메시지가 폴링 {@code body.error}로 유출되는 경로를
     * 시그니처에서 차단한다(상세는 호출부가 로그로만 남긴다).
     */
    public void markFailed(String taskId, long userId, long dailyRecordId, ExceptionType failureType,
                           String callbackTokenHash) {
        if (!TASK_FAILURE_TYPES.contains(failureType)) {
            throw new IllegalStateException("task 실패 분류 타입이 아닙니다: " + failureType);
        }
        timelineTaskStore.save(taskId,
                TimelineDraftTask.failed(userId, dailyRecordId, failureType.code(), callbackTokenHash),
                TERMINAL_TTL);
        timelineMetrics.recordTerminalFailed();
    }

    /** Redis task의 numeric code를 task-local 타입으로 제한해 해석한다. */
    public static ExceptionType resolveFailureType(Integer code) {
        if (code == null) {
            return ExceptionType.DRAFT_TASK_FAILURE_FALLBACK;
        }
        return TASK_FAILURE_TYPES_BY_CODE.getOrDefault(code, ExceptionType.DRAFT_TASK_FAILURE_FALLBACK);
    }

    public Optional<TimelineDraftTask> find(String taskId) {
        return timelineTaskStore.find(taskId);
    }

    /**
     * task별 callback token을 인증 시점에 원자 소비한다. false면 이미 사용된 token이다.
     * 소비 뒤 처리 실패에도 marker를 되돌리지 않는 at-most-once admission 계약이다.
     */
    public boolean consumeCallbackToken(String taskId) {
        return timelineTaskStore.consumeCallbackToken(taskId, CALLBACK_TOKEN_USE_TTL);
    }

    long countStuckProcessing(Instant now, Duration stuckAfter) {
        if (stuckAfter.isZero() || stuckAfter.isNegative()
                || stuckAfter.compareTo(PROCESSING_TTL) >= 0) {
            throw new IllegalArgumentException(
                    "stuck threshold는 0보다 크고 PROCESSING TTL보다 짧아야 합니다: " + stuckAfter);
        }
        return timelineTaskStore.countStuckProcessing(now, stuckAfter, PROCESSING_TTL);
    }
}
