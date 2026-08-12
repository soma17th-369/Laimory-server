package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 결과를 저장한다. RESULT token을 CALLBACK token으로 먼저 교체해 동시 writer를 하나로 제한하며,
 * MySQL 저장 실패 시 가능한 경우 이전 RESULT token으로 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineAiResultService {

    /** AI Event text의 DB 컬럼 상한 — bounded redaction이 이 길이를 넘지 않게 보장한다. */
    private static final int EVENT_TEXT_MAX_LENGTH = 255;

    private final TimelineTaskService timelineTaskService;
    private final TimelineAiResultTransactionService timelineAiResultTransactionService;
    private final PrivacyRedactor privacyRedactor;

    public AiTimelineResultResponse storeResult(String applicationVersion, String taskId, String taskToken,
                                                AiTimelineResultRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
        if (!task.matchesToken(taskToken)) {
            log.warn("ai result token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("ai result on terminal task: taskId={} status={}", taskId, task.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (task.stage() != ProcessStage.RESULT_PENDING) {
            log.warn("ai result on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        // DB를 보지 않는 형식 검증을 먼저 해 transaction을 열지 않고 400으로 끝낸다.
        TimelineAiResultTransactionService.requireValidShape(request);

        // token 선점 전에 Event text의 bounded 치환 사본을 만든다 — 여기서 실패하면 token 미선점·
        // RESULT_PENDING 유지로 끝난다(원문 fallback 금지). 이후 단계는 치환본만 본다.
        AiTimelineResultRequest redacted = redactEventTexts(request);

        String callbackToken = TaskTokens.generate();
        String callbackTokenHash = TaskTokens.hash(callbackToken);
        TimelineDraftTask claimed = task.withTokenAndStage(callbackTokenHash, ProcessStage.CALLBACK_PENDING);
        if (!timelineTaskService.replaceProcessing(taskId, task, claimed)) {
            log.warn("ai result token rotation lost race: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        try {
            timelineAiResultTransactionService.store(taskId, task.subjectId(), task.dailyRecordId(), redacted);
        } catch (RuntimeException storageFailure) {
            try {
                if (!timelineTaskService.replaceProcessing(taskId, claimed, task)) {
                    log.warn("ai result token rollback skipped after storage failure: taskId={}", taskId);
                }
            } catch (RuntimeException tokenFailure) {
                storageFailure.addSuppressed(tokenFailure);
                log.warn("ai result token rollback failed: taskId={}", taskId, tokenFailure);
            }
            throw storageFailure;
        }

        return new AiTimelineResultResponse(callbackToken);
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
                // token 선점 전이라 RESULT_PENDING이 유지된다. 메시지에 원문·매치 내용을 담지 않는다.
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
