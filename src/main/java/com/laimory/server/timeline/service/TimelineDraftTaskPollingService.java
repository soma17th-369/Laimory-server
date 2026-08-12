package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 폴링(GET) 오케스트레이터. Redis task 상태를 읽고, SUCCESS면 task에 저장된 dailyRecordId로 그날 전체
 * 타임라인을 조립해 반환한다. task 없음(만료)은 404(-1001)다.
 *
 * <p>task owner와 request subjectId를 상태 분기 <b>전에</b> 대조한다 — 타 사용자의 taskId는 상태와
 * 무관하게 404(-1001)로 은닉한다(존재 여부 비노출).
 *
 * <p>SUCCESS 결과는 (subjectId, recordDate) 재조회로 찾지 않는다 — record 삭제 후 같은 날짜가 재생성되면
 * 과거 task가 새 기록을 반환하는 오조회가 생기기 때문이다. ID 조회가 실패하는 경우(결과 record 삭제됨
 * 또는 결과 record의 owner 불일치)는 404(-404)로
 * 응답한다 — "task 자체가 없음"(-1001)과 클라이언트가 구분한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineDraftTaskPollingService {

    private final TimelineTaskService timelineTaskService;
    private final DailyRecordService dailyRecordService;
    private final DailyTimelineService dailyTimelineService;
    private final Clock clock;

    public DraftTaskStatusResponse poll(String applicationVersion, SubjectId subjectId, String taskId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));

        // 소유권 대조는 상태 분기보다 먼저 — 타인 task는 상태(PROCESSING/FAILED/SUCCESS)조차 노출하지 않는다.
        if (!task.subjectId().equals(subjectId)) {
            // 인증된 사용자가 남의 taskId를 제시한 상황은 정상 수명주기(만료 재폴링)와 달리 열거/유출 시도
            // 신호다 — 응답은 동일하게 1001로 은닉하되 관측용 WARN만 남긴다(CALLBACK_TOKEN_MISMATCH 선례).
            log.warn("foreign task poll rejected: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND);
        }

        return switch (task.status()) {
            case PROCESSING -> DraftTaskStatusResponse.processing(elapsedSeconds(task.processingStartedAt()));
            // read-side 유출 방어: 알려진 실패 코드가 아니면 -1011로 대체.
            case FAILED -> DraftTaskStatusResponse.failed(
                    TimelineTaskService.resolveFailureType(task.error()).code());
            case SUCCESS -> {
                DailyRecord record = findResultRecord(task, subjectId);
                DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(record.getDailyRecordId());
                yield DraftTaskStatusResponse.success(result);
            }
        };
    }

    /**
     * PROCESSING의 AI 작업 대기 경과 시간(완료된 초). 시계 역행·future timestamp는 0으로 clamp한다
     * (음수 비노출 — 완전한 단조 증가는 계약하지 않음). millis 변환·int cast 없이 long seconds로만
     * 계산해 overflow를 만들지 않는다.
     */
    private long elapsedSeconds(Instant processingStartedAt) {
        Duration elapsed = Duration.between(processingStartedAt, clock.instant());
        return elapsed.isNegative() ? 0L : elapsed.getSeconds();
    }

    /** SUCCESS task의 결과 record를 ID로만 찾는다. 삭제됨·비소유는 전부 0404로 은닉한다. */
    private DailyRecord findResultRecord(TimelineDraftTask task, SubjectId subjectId) {
        return dailyRecordService.findById(task.dailyRecordId())
                .filter(record -> record.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_RESULT_NOT_FOUND));
    }
}
