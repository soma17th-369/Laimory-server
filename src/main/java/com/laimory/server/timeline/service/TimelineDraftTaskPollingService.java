package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 폴링(GET) 오케스트레이터. Redis task 상태를 읽고, SUCCESS면 (userId, recordDate)로 daily record를 찾아
 * 그날 전체 타임라인을 조립해 반환한다. task 없음(만료)은 404.
 */
@Service
@RequiredArgsConstructor
public class TimelineDraftTaskPollingService {

    private final TimelineTaskService timelineTaskService;
    private final DailyRecordService dailyRecordService;
    private final DailyTimelineService dailyTimelineService;

    public DraftTaskStatusResponse poll(String applicationVersion, String taskId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found: " + taskId));

        return switch (task.status()) {
            case PROCESSING -> DraftTaskStatusResponse.processing();
            case FAILED -> DraftTaskStatusResponse.failed(task.error());
            case SUCCESS -> {
                DailyRecord record = dailyRecordService
                        .findByUserIdAndRecordDate(TimelineDefaults.DEFAULT_USER_ID, task.recordDate())
                        .orElseThrow(() -> new IllegalStateException(
                                "daily record missing for SUCCESS task: " + taskId));
                DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(record.getDailyRecordId());
                yield DraftTaskStatusResponse.success(result);
            }
        };
    }
}
