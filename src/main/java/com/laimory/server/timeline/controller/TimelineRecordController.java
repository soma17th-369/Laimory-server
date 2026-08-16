package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordListResponse;
import com.laimory.server.timeline.dto.SaveDailyRecordRequest;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.service.DailyTimelineService;
import com.laimory.server.timeline.service.TimelineDeletionService;
import com.laimory.server.timeline.service.TimelineEventEditService;
import com.laimory.server.timeline.service.TimelineSaveService;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 확정 타임라인 기록 조회·편집 API 구현. HTTP 문서·계약은 {@link TimelineRecordApi}.
 *
 * <p>컨트롤러 파라미터의 subjectId는 클라이언트 값이 아니라 {@code @CurrentSubject}가 JWT principal을
 * 해석한 결과다. 소유권 검사·S3 key 유도는 전부 이 subject를 기준으로 한다.
 */
@RestController
@RequiredArgsConstructor
public class TimelineRecordController implements TimelineRecordApi {

    private final DailyTimelineService dailyTimelineService;
    private final TimelineEventEditService timelineEventEditService;
    private final TimelineDeletionService timelineDeletionService;
    private final TimelineSaveService timelineSaveService;

    @Override
    public ResponseEntity<ApiResponse<DailyTimelinesResponse>> getDailyTimelines(
            String applicationVersion, UUID subjectId) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getDailyTimelines(applicationVersion, subjectId)));
    }

    @Override
    public ResponseEntity<ApiResponse<DailyTimelineResponse>> getDailyTimeline(
            String applicationVersion, UUID subjectId, Long dailyRecordId) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getDailyTimeline(applicationVersion, subjectId, dailyRecordId)));
    }

    @Override
    public ResponseEntity<ApiResponse<DailyTimelineResponse>> getDailyTimelineByDate(
            String applicationVersion, UUID subjectId, LocalDate recordDate) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getDailyTimeline(applicationVersion, subjectId, recordDate)));
    }

    @Override
    public ResponseEntity<ApiResponse<MonthlyDailyRecordListResponse>> getMonthlyDailyRecords(
            String applicationVersion, UUID subjectId, int year, int month) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getMonthlyDailyRecords(applicationVersion, subjectId, year, month)));
    }

    @Override
    public ResponseEntity<ApiResponse<TimelineEventResponse>> getTimelineEvent(
            String applicationVersion, UUID subjectId, Long timelineEventId) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getTimelineEvent(applicationVersion, subjectId, timelineEventId)));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> updateTimelineEvent(
            String applicationVersion, UUID subjectId, Long timelineEventId, UpdateTimelineEventRequest request) {
        timelineEventEditService.updateEvent(applicationVersion, subjectId, timelineEventId, request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> updateTimelineEventMemo(
            String applicationVersion, UUID subjectId, Long timelineEventId, UpdateTimelineEventMemoRequest request) {
        timelineEventEditService.updateMemo(applicationVersion, subjectId, timelineEventId, request.memo());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteTimelineEvent(
            String applicationVersion, UUID subjectId, Long timelineEventId) {
        timelineDeletionService.deleteEvent(applicationVersion, subjectId, timelineEventId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> detachTimelineEventItem(
            String applicationVersion, UUID subjectId, Long timelineEventId, Long timelineItemId) {
        timelineDeletionService.detachEventItem(applicationVersion, subjectId, timelineEventId, timelineItemId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteDailyRecord(
            String applicationVersion, UUID subjectId, Long dailyRecordId) {
        timelineDeletionService.deleteDailyRecord(applicationVersion, subjectId, dailyRecordId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteDailyRecordByDate(
            String applicationVersion, UUID subjectId, LocalDate recordDate) {
        timelineDeletionService.deleteDailyRecordByDate(applicationVersion, subjectId, recordDate);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> saveDailyRecord(
            String applicationVersion, UUID subjectId, LocalDate recordDate, SaveDailyRecordRequest request) {
        timelineSaveService.save(applicationVersion, subjectId, recordDate, request.emotionType());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
