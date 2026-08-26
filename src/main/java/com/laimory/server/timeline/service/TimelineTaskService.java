package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * timeline draft 작업 상태 leaf 서비스. 자신과 1:1인 TimelineTaskStore에만 접근한다.
 * 처리중(PROCESSING)은 3분, 종결 상태(SUCCESS/FAILED)는 24시간 TTL로 보관한다.
 * PROCESSING 만료는 Redis key 소멸이지 FAILED 전이가 아니다 — callback 없이 만료된 task의
 * 이후 폴링·콜백은 404(-1001)로 수렴하며, scheduler가 FAILED로 복구하지 않는다.
 *
 * <p>현재 task token은 hash로 검증하고, PROCESSING token hash와 내부 단계는 task JSON 전체 CAS로 교체한다.
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

    // AI 접수는 202 즉시 반환, 정상 inference·callback은 3분 내 종료가 운영 목표 — 사용자에게
    // 무기한 PROCESSING을 노출하지 않기 위해 callback 없는 task는 이 TTL이 회수한다.
    // transport 구현이 접수 대기 상한을 이 값에 맞춰 기동 시 검증한다(http는 같은 패키지, agentcore는
    // com.laimory.server.ai.agentcore) — 그래서 package-private이 아니라 public이다.
    public static final Duration PROCESSING_TTL = Duration.ofMinutes(3);
    private static final Duration TERMINAL_TTL = Duration.ofHours(24);

    private final TimelineTaskStore timelineTaskStore;
    private final TimelineMetrics timelineMetrics;

    /**
     * dailyRecordId는 선생성된 DailyRecord의 ID다 — 세 상태 모두 보존되며 폴링·콜백 전이의 기준이다.
     * processingStartedAt은 폴링의 AI 작업 대기 경과 시간 기준(PROCESSING 전용 — terminal은 보존하지 않음).
     * subjectId는 task owner다 — 세 상태 전이 모두 필수로 받아 보존한다(폴링 소유권 대조·콜백 전이 기준).
     */
    public void createProcessing(String taskId, UUID subjectId, long dailyRecordId,
                                 TimelineDraftTask.TimelineWindow timelineWindow,
                                 String tokenHash, Instant processingStartedAt) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.processing(subjectId, dailyRecordId, timelineWindow,
                        tokenHash, processingStartedAt),
                PROCESSING_TTL);
        timelineMetrics.recordDraftCreated();
    }

    /**
     * PROCESSING task의 TTL만 3분으로 다시 확보한다(JSON은 그대로 재저장 — {@code processingStartedAt}
     * 보존이라 폴링 {@code elapsedSeconds} 의미가 바뀌지 않는다).
     *
     * <p>AI가 입력을 조회한 시점부터 추론이 시작되고, 결과 저장 뒤에도 콜백이 남는다 — 최초 3분을
     * dispatch 시점부터 소진시키면 정상 처리가 마지막 단계에서 만료로 잘릴 수 있다.
     */
    public boolean replaceProcessing(String taskId, TimelineDraftTask expected, TimelineDraftTask replacement) {
        if (expected.status() != TaskStatus.PROCESSING || replacement.status() != TaskStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING task만 교체할 수 있습니다");
        }
        return timelineTaskStore.replaceIfUnchanged(taskId, expected, replacement, PROCESSING_TTL);
    }

    /** callback이 읽은 PROCESSING task가 그대로일 때만 SUCCESS로 종결한다. */
    public boolean markSuccessIfCurrent(String taskId, TimelineDraftTask task) {
        boolean saved = timelineTaskStore.replaceIfUnchanged(taskId, task,
                TimelineDraftTask.success(task.subjectId(), task.dailyRecordId(), task.tokenHash()), TERMINAL_TTL);
        if (saved) {
            timelineMetrics.recordTerminalSuccess();
        }
        return saved;
    }

    /**
     * task를 FAILED로 종결한다. {@code failureType}은 task 실패 분류 세 타입만
     * 허용한다 — raw 문자열을 받지 않아, 내부 예외 메시지가 폴링 {@code body.error}로 유출되는 경로를
     * 시그니처에서 차단한다(상세는 호출부가 로그로만 남긴다).
     */
    public void markFailed(String taskId, UUID subjectId, long dailyRecordId, ExceptionType failureType,
                           String tokenHash) {
        if (!TASK_FAILURE_TYPES.contains(failureType)) {
            throw new IllegalStateException("task 실패 분류 타입이 아닙니다: " + failureType);
        }
        timelineTaskStore.save(taskId,
                TimelineDraftTask.failed(subjectId, dailyRecordId, failureType.code(), tokenHash),
                TERMINAL_TTL);
        timelineMetrics.recordTerminalFailed();
    }

    /** callback이 읽은 PROCESSING task가 그대로일 때만 FAILED로 종결한다. */
    public boolean markFailedIfCurrent(String taskId, TimelineDraftTask task, ExceptionType failureType) {
        if (!TASK_FAILURE_TYPES.contains(failureType)) {
            throw new IllegalStateException("task 실패 분류 타입이 아닙니다: " + failureType);
        }
        boolean saved = timelineTaskStore.replaceIfUnchanged(taskId, task,
                TimelineDraftTask.failed(task.subjectId(), task.dailyRecordId(), failureType.code(), task.tokenHash()),
                TERMINAL_TTL);
        if (saved) {
            timelineMetrics.recordTerminalFailed();
        }
        return saved;
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
     * 요청 사용자가 소유한 현재 PROCESSING taskId 목록(생성 최신순)이다. 사용자 index는 조회 후보일
     * 뿐이고 task JSON의 status/owner가 권위다 — stale 후보(만료·terminal·타인 소유)는 store가 응답에서
     * 제외하고 best-effort로 정리한다.
     */
    public List<String> findProcessingTaskIds(UUID subjectId) {
        return timelineTaskStore.findProcessingTaskIds(subjectId);
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
