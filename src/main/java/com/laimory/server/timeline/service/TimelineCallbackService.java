package com.laimory.server.timeline.service;

import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 카드 생성 콜백 오케스트레이터. task 로드 + 멱등 + 콜백 status 분기 + 검증/영속/Redis 전이를 합성한다.
 *
 * <p>검증 실패·SAVED 재확인 실패는 잡아서 task를 FAILED로 기록한다(콜백 자체는 200으로 응답 — 결과를 '기록'한 것).
 * task 없음만 404, Callback-Token 누락/불일치는 401. 콜백 바디 status가 SUCCESS/FAILED가 아니면 잘못된 요청(IAE → 400).
 * recordDate는 콜백 바디에 없으므로 task에서 가져온다.
 */
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    private final TimelineTaskService timelineTaskService;
    private final CardSuggestionValidator cardSuggestionValidator;
    private final DailyTimelineService dailyTimelineService;

    public void handleCallback(String applicationVersion, String taskId,
                               String callbackToken, DraftTaskCallbackRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found: " + taskId));

        // 멱등: 이미 종결(SUCCESS/FAILED)된 task면 재처리하지 않는다(콜백 재전송 방어 — 같은 토큰 재사용도 여기서 무효화).
        if (task.status() != TaskStatus.PROCESSING) {
            return;
        }

        // PROCESSING task만 토큰 검증: 원문 토큰의 해시를 저장된 해시와 상수시간 비교.
        if (!CallbackTokens.matches(callbackToken, task.callbackTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid callback token");
        }

        LocalDate recordDate = task.recordDate();

        // AI가 자신의 실패를 보고한 경우: 그대로 FAILED 기록.
        if (request.status() == TaskStatus.FAILED) {
            timelineTaskService.markFailed(taskId, recordDate, request.error());
            return;
        }
        if (request.status() != TaskStatus.SUCCESS) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }

        // SUCCESS: 검증 후 영속. 검증/SAVED 재확인(appendDailyTimeline 내부 가드) 실패는 FAILED로 기록한다.
        try {
            cardSuggestionValidator.validate(request.sourceItems(), request.cards());
            dailyTimelineService.appendDailyTimeline(
                    TimelineDefaults.DEFAULT_USER_ID, recordDate, request.sourceItems(), request.cards());
            timelineTaskService.markSuccess(taskId, recordDate);
        } catch (IllegalArgumentException | IllegalStateException e) {
            timelineTaskService.markFailed(taskId, recordDate, e.getMessage());
        }
    }
}
