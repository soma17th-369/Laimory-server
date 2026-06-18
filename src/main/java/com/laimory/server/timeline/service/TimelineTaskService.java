package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.LocalDate;
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

    private final TimelineTaskStore timelineTaskStore;

    public void createProcessing(String taskId, LocalDate recordDate, String callbackTokenHash) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.processing(recordDate, callbackTokenHash), PROCESSING_TTL);
    }

    public void markSuccess(String taskId, LocalDate recordDate) {
        timelineTaskStore.save(taskId, TimelineDraftTask.success(recordDate), TERMINAL_TTL);
    }

    public void markFailed(String taskId, LocalDate recordDate, String error) {
        timelineTaskStore.save(taskId, TimelineDraftTask.failed(recordDate, error), TERMINAL_TTL);
    }

    public Optional<TimelineDraftTask> find(String taskId) {
        return timelineTaskStore.find(taskId);
    }
}
