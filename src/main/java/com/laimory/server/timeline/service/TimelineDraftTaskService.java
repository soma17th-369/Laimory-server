package com.laimory.server.timeline.service;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.SourceItemDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 작성 작업 생성(POST) 오케스트레이터. SAVED 거절 + taskId 발급 + PROCESSING 기록 + AI 디스패치를 합성한다.
 *
 * <p>요청 스레드는 디스패치를 블로킹하지 않는다(dispatch는 fire-and-forget; v1 no-op).
 * 같은 날짜로 PROCESSING task가 떠 있는 동안 두 번째 POST가 와도 둘 다 통과할 수 있다(plan 모호점 4, MVP 수용).
 */
@Service
public class TimelineDraftTaskService {

    // 콜백 절대 URL 구성용 path 템플릿(1st %s=applicationVersion, 2nd %s=taskId). 컨트롤러 콜백 매핑과 동일해야 한다.
    // 서버간 통신이므로 /s prefix. AI는 POST가 들어온 것과 같은 버전 경로로 콜백한다.
    private static final String CALLBACK_PATH_TEMPLATE =
            "/s/api/%s/timeline/daily-records/draft-tasks/%s/callback";

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
     * 작성 작업을 만들고 taskId를 반환한다. 이미 SAVED인 daily record면 IllegalStateException(409)으로 거절한다.
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
                    throw new IllegalStateException("daily record already SAVED: " + record.getId());
                });

        String taskId = UUID.randomUUID().toString();
        timelineTaskService.createProcessing(taskId, recordDate);

        // AI가 같은 버전 경로로 콜백하도록 요청 버전을 콜백 URL에 그대로 싣는다.
        String callbackUrl = callbackBaseUrl + String.format(CALLBACK_PATH_TEMPLATE, applicationVersion, taskId);
        cardSuggestionDispatcher.dispatch(taskId, sourceItems, callbackUrl);

        return taskId;
    }
}
