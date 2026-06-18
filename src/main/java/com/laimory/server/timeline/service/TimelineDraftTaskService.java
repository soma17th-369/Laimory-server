package com.laimory.server.timeline.service;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.SourceItemDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 작성 작업 생성(POST) 오케스트레이터. SAVED 거절 + taskId 발급 + PROCESSING 기록 + AI 디스패치를 합성한다.
 *
 * <p>요청 스레드는 디스패치를 블로킹하지 않는다(dispatch는 fire-and-forget; v1 no-op).
 * 같은 날짜로 PROCESSING task가 떠 있는 동안 두 번째 POST가 와도 둘 다 통과할 수 있다(plan 모호점 4, MVP 수용).
 */
@Service
public class TimelineDraftTaskService {

    private final DailyRecordService dailyRecordService;
    private final TimelineTaskService timelineTaskService;
    private final CardSuggestionDispatcher cardSuggestionDispatcher;
    private final String callbackBaseUrl;

    public TimelineDraftTaskService(DailyRecordService dailyRecordService,
                                    TimelineTaskService timelineTaskService,
                                    CardSuggestionDispatcher cardSuggestionDispatcher,
                                    @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.dailyRecordService = dailyRecordService;
        this.timelineTaskService = timelineTaskService;
        this.cardSuggestionDispatcher = cardSuggestionDispatcher;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    /**
     * 작성 작업을 만들고 taskId를 반환한다. 이미 SAVED인 daily record면 409(ResponseStatusException)로 거절한다.
     * dispatch가 동기 예외를 던지면 task를 FAILED로 고정하고 taskId는 정상 반환한다(클라가 폴링으로 결과 확인).
     */
    public String createDraftTask(String applicationVersion, LocalDate recordDate,
                                  List<SourceItemDto> sourceItems) {
        if (recordDate == null) {
            throw new IllegalArgumentException("recordDate is required");
        }
        if (sourceItems == null || sourceItems.isEmpty()) {
            throw new IllegalArgumentException("sourceItems is required");
        }

        dailyRecordService.findByUserIdAndRecordDate(TimelineDefaults.DEFAULT_USER_ID, recordDate)
                .filter(record -> record.getStatus() == DailyRecordStatus.SAVED)
                .ifPresent(record -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "daily record already SAVED: " + record.getId());
                });

        String taskId = UUID.randomUUID().toString();
        // one-time 콜백 토큰: 원문은 AI에만 전달하고 서버는 해시만 보관한다.
        String callbackToken = CallbackTokens.generate();
        timelineTaskService.createProcessing(taskId, recordDate, CallbackTokens.hash(callbackToken));

        // AI가 같은 버전 경로로 콜백하도록 요청 버전을 콜백 URL에 싣는다. 서버간 통신 prefix는 ApiUrls 헬퍼로 만든다.
        String callbackUrl = callbackBaseUrl
                + ApiUrls.serverApi(applicationVersion)
                + "/timeline/drafts/" + taskId + "/callback";
        // dispatch는 fire-and-forget이어야 하지만, 실제 구현이 동기 예외를 던질 경우 task가 PROCESSING으로 고아가 되지
        // 않도록 FAILED로 고정한다. taskId는 정상 반환해 클라가 폴링으로 실패를 확인할 수 있게 한다.
        try {
            cardSuggestionDispatcher.dispatch(taskId, callbackToken, sourceItems, callbackUrl);
        } catch (RuntimeException e) {
            timelineTaskService.markFailed(taskId, recordDate,
                    "card suggestion dispatch failed: " + e.getMessage());
        }

        return taskId;
    }
}
