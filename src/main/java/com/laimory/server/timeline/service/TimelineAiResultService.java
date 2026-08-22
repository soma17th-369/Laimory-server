package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.timeline.AiTimelineResultDigest;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.entity.TimelineDraftTask.RetryReceipt;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 결과를 저장한다.
 *
 * <p>순서가 load-bearing이다. 선점 CAS는 <b>token을 바꾸지 않고</b> payload 지문만 receipt에 남겨
 * 동시 writer를 하나로 제한한다 — MySQL이 실패해도 AI가 쥔 token이 그대로라 재요청이 특수 경로 없이
 * 정상 경로로 다시 돈다. callback token 회전과 {@code committedAt} 기록은 <b>commit 뒤 한 번의 CAS</b>로
 * 함께 일어난다.
 *
 * <p>{@code committedAt}의 존재가 "graph가 확정됐다"의 유일한 증거다. 선점과 commit 사이에 프로세스가
 * 죽으면 receipt에 {@code committedAt}이 없으므로 어떤 재요청도 멱등 성공으로 오인되지 않는다.
 *
 * <p>응답 유실 뒤 소비된 result token으로 <b>같은 결과</b>가 다시 오면 MySQL을 건드리지 않고 새 callback
 * token만 재발급한다(멱등). 창은 첫 요청 도착 시각 기준 절대 마감이며 재발급으로 미끄러지지 않는다.
 */
@Slf4j
@Service
public class TimelineAiResultService {

    /** AI Event text의 DB 컬럼 상한 — bounded redaction이 이 길이를 넘지 않게 보장한다. */
    private static final int EVENT_TEXT_MAX_LENGTH = 255;

    private final TimelineTaskService timelineTaskService;
    private final TimelineAiResultTransactionService timelineAiResultTransactionService;
    private final PrivacyRedactor privacyRedactor;
    private final Clock clock;
    private final Duration retryWindow;

    public TimelineAiResultService(TimelineTaskService timelineTaskService,
                                   TimelineAiResultTransactionService timelineAiResultTransactionService,
                                   PrivacyRedactor privacyRedactor,
                                   Clock clock,
                                   @Value("${app.ai.retry-window:15s}") Duration retryWindow) {
        this.timelineTaskService = timelineTaskService;
        this.timelineAiResultTransactionService = timelineAiResultTransactionService;
        this.privacyRedactor = privacyRedactor;
        this.clock = clock;
        this.retryWindow = retryWindow;
    }

    public AiTimelineResultResponse storeResult(String applicationVersion, String taskId, String taskToken,
                                                AiTimelineResultRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
        if (!task.matchesToken(taskToken)) {
            // 현재 slot과 불일치 — 소비된 result token의 재시도인지 판정한다(창 검사도 이 경로에만 있다).
            return replayStoredResult(taskId, task, taskToken, request);
        }
        return storeFreshResult(taskId, task, request);
    }

    private AiTimelineResultResponse storeFreshResult(String taskId, TimelineDraftTask task,
                                                      AiTimelineResultRequest request) {
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("ai result on terminal task: taskId={} status={}", taskId, task.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (task.stage() != ProcessStage.RESULT_PENDING) {
            log.warn("ai result on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (task.retryReceipt() != null && task.retryReceipt().resultDigest() != null) {
            // 다른 시도가 이미 선점했다. 창이 지난 선점도 재선점하지 않는다 — 그 시도가 아직 살아
            // transaction 중일 수 있고, 겹쳐 돌면 graph가 중복 저장된다.
            log.warn("ai result replay while uncommitted: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        AiTimelineResultRequest redacted = validatedAndRedacted(request);

        // 선점: token·stage는 그대로 두고 지문만 남긴다. 실패해도 AI의 token이 살아 있어 재시도가 정상 경로다.
        RetryReceipt claimReceipt = claimReceipt(task, redacted);
        TimelineDraftTask claimed = task.withRetryReceipt(claimReceipt);
        if (!timelineTaskService.replaceProcessing(taskId, task, claimed)) {
            log.warn("ai result claim lost race: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        try {
            timelineAiResultTransactionService.store(taskId, task.subjectId(), task.dailyRecordId(), redacted);
        } catch (RuntimeException storageFailure) {
            releaseClaim(taskId, claimed, task, storageFailure);
            throw storageFailure;
        }

        String callbackToken = TaskTokens.generate();
        TimelineDraftTask committed = claimed
                .withTokenAndStage(TaskTokens.hash(callbackToken), ProcessStage.CALLBACK_PENDING)
                .withRetryReceipt(claimReceipt.committedAt(clock.instant()));
        if (!timelineTaskService.replaceProcessing(taskId, claimed, committed)) {
            // 선점 뒤 task를 바꿀 수 있는 경로가 없으므로 여기 도달하면 TTL 만료뿐이다. graph는 남고
            // task는 복구되지 않는다 — 응답 유실과 같은 결과라 재요청도 받아줄 수 없다.
            log.error("ai result commit rotation lost task: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        return AiTimelineResultResponse.stored(callbackToken);
    }

    /**
     * 소비된 result token으로 같은 결과가 다시 온 경우 MySQL을 건드리지 않고 callback token만 재발급한다.
     * 어느 조건이든 어긋나면 오늘과 같은 401/409다 — 저장이 끝났다는 증거({@code committedAt}) 없이는
     * 성공으로 처리하지 않는다.
     */
    private AiTimelineResultResponse replayStoredResult(String taskId, TimelineDraftTask task,
                                                        String taskToken, AiTimelineResultRequest request) {
        RetryReceipt receipt = task.retryReceipt();
        if (receipt == null || !receipt.committed()
                || !task.matchesPreviousToken(taskToken)
                || task.stage() != ProcessStage.CALLBACK_PENDING) {
            log.warn("ai result token mismatch: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (task.retryWindowExpired(clock.instant())) {
            log.warn("ai result retry window expired: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }

        AiTimelineResultRequest redacted = validatedAndRedacted(request);
        if (!receipt.matchesResultDigest(AiTimelineResultDigest.of(redacted))) {
            // 같은 자격으로 다른 결과를 밀어넣는 요청이다. 저장된 graph와 직전 callback token은 그대로 둔다.
            log.warn("ai result replay payload mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        // 제자리 회전 — stage는 그대로고 receipt(창 포함)도 그대로다. 직전 callback token은 즉시 무효가 된다.
        String callbackToken = TaskTokens.generate();
        TimelineDraftTask reissued =
                task.withTokenAndStage(TaskTokens.hash(callbackToken), ProcessStage.CALLBACK_PENDING);
        if (!timelineTaskService.replaceProcessing(taskId, task, reissued)) {
            // 진 쪽이 방금 만든 token은 저장된 적이 없어 돌려주면 callback에서 401이 된다 — 재조회로
            // 멱등 성공 처리하지 않는다(callback race와 달리 응답 자체가 서버가 만든 비밀이다).
            log.warn("ai result replay lost race: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        log.info("ai result replay reissued callback token: taskId={}", taskId);
        return AiTimelineResultResponse.alreadyProcessed(callbackToken);
    }

    /**
     * DB를 보지 않는 형식 검증 뒤 Event text의 bounded 치환 사본을 만든다. 두 경로가 같은 순서로 쓴다 —
     * 재시도의 지문이 치환본 기준이라 신규 저장과 같은 변환을 거쳐야 값이 일치한다.
     */
    private AiTimelineResultRequest validatedAndRedacted(AiTimelineResultRequest request) {
        TimelineAiResultTransactionService.requireValidShape(request);
        return redactEventTexts(request);
    }

    /**
     * 선점 receipt. {@code previousTokenHash}는 지금의 현재 token hash다 — 회전 뒤에 이 값이 "소비된
     * result token"이 된다. {@code retryableUntil}은 여기서 한 번만 정하고 이후 재발급이 갱신하지 않는다.
     */
    private RetryReceipt claimReceipt(TimelineDraftTask task, AiTimelineResultRequest redacted) {
        return new RetryReceipt(task.tokenHash(), AiTimelineResultDigest.of(redacted),
                null, clock.instant().plus(retryWindow));
    }

    /** 저장 실패 뒤 선점을 되돌린다. 실패해도 예외를 덮지 않는다 — 원인은 storage 쪽이다. */
    private void releaseClaim(String taskId, TimelineDraftTask claimed, TimelineDraftTask original,
                              RuntimeException storageFailure) {
        try {
            if (!timelineTaskService.replaceProcessing(taskId, claimed, original)) {
                log.warn("ai result claim release skipped after storage failure: taskId={}", taskId);
            }
        } catch (RuntimeException releaseFailure) {
            storageFailure.addSuppressed(releaseFailure);
            log.warn("ai result claim release failed: taskId={} detail={}",
                    taskId, releaseFailure.getClass().getSimpleName());
        }
    }

    /**
     * Event title/subtitle/question만 bounded 치환한 요청 사본을 만든다(wire DTO 필드 집합 불변).
     * 치환 전에 persistence와 같은 normalize(trim·trimToNull)를 적용한다 — shape 검증이 trim 길이로
     * 통과시킨 앞뒤 공백이 255 절단 지점을 앞당겨 token까지 잘려나가는 것을 막는다. transaction 쪽
     * 재-trim과 이중 적용돼도 의미가 같다. subtitle/question은 nullable — null은 그대로 유지된다.
     * 치환 결과는 255자 이하가 보장돼 이후 transaction의 trim·길이 검증과 충돌하지 않는다.
     */
    private AiTimelineResultRequest redactEventTexts(AiTimelineResultRequest request) {
        List<AiTimelineResultRequest.Event> events = new ArrayList<>(request.events().size());
        for (int i = 0; i < request.events().size(); i++) {
            AiTimelineResultRequest.Event event = request.events().get(i);
            String title = privacyRedactor.redactText(event.title().trim(), EVENT_TEXT_MAX_LENGTH).text();
            if (title.isBlank()) {
                // 필수 title이 치환 후 blank면 shape 위반과 같은 400 계열로 거절한다(원문 fallback 금지).
                // 선점 전이라 RESULT_PENDING이 유지된다. 메시지에 원문·매치 내용을 담지 않는다.
                throw new IllegalArgumentException("event title is blank after redaction: index=" + i);
            }
            events.add(new AiTimelineResultRequest.Event(
                    event.eventType(), title,
                    privacyRedactor.redactText(trimToNull(event.subtitle()), EVENT_TEXT_MAX_LENGTH).text(),
                    privacyRedactor.redactText(trimToNull(event.question()), EVENT_TEXT_MAX_LENGTH).text(),
                    event.startAt(), event.endAt(), event.sourceRawIds()));
        }
        return new AiTimelineResultRequest(events);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
