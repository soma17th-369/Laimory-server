package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 폴링(GET) 오케스트레이터. Redis task 상태를 읽고, SUCCESS면 task에 저장된 dailyRecordId로 그날 전체
 * 타임라인을 조립해 반환한다. task 없음(만료)은 404(ERROR_1001)다.
 *
 * <p>SUCCESS 결과는 (userId, recordDate) 재조회로 찾지 않는다 — record 삭제 후 같은 날짜가 재생성되면
 * 과거 task가 새 기록을 반환하는 오조회가 생기기 때문이다. ID 조회가 실패하는 경우(결과 record 삭제됨,
 * 또는 dailyRecordId가 없는 배포 전 legacy SUCCESS task)는 404(ERROR_0404)로 응답한다 —
 * "task 자체가 없음"(ERROR_1001)과 클라이언트가 구분한다.
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
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));

        return switch (task.status()) {
            case PROCESSING -> DraftTaskStatusResponse.processing();
            // read-side 유출 방어: 알려진 실패 코드가 아니면(과거 raw 잔존 — FAILED TTL 24h 내) ERROR_1011로 대체.
            case FAILED -> DraftTaskStatusResponse.failed(
                    ErrorCode.isTaskFailureCode(task.error()) ? task.error() : ErrorCode.ERROR_1011.name());
            case SUCCESS -> {
                DailyRecord record = findResultRecord(task);
                DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(record.getDailyRecordId());
                yield DraftTaskStatusResponse.success(result);
            }
        };
    }

    /** SUCCESS task의 결과 record를 ID로만 찾는다. legacy(ID 부재)·삭제됨·비소유는 전부 0404로 은닉한다. */
    private DailyRecord findResultRecord(TimelineDraftTask task) {
        Long dailyRecordId = task.dailyRecordId();
        if (dailyRecordId == null) {
            throw new BusinessException(ExceptionType.DRAFT_RESULT_NOT_FOUND);
        }
        return dailyRecordService.findById(dailyRecordId)
                .filter(record -> record.getUserId() == TimelineDefaults.DEFAULT_USER_ID)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_RESULT_NOT_FOUND));
    }
}
