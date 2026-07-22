package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.service.DailyTimelineService;
import com.laimory.server.timeline.service.TimelineDeletionService;
import com.laimory.server.timeline.service.TimelineEventEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 확정 타임라인 기록 조회·편집 API 구현. HTTP 문서·계약은 {@link TimelineRecordApi}.
 *
 * <p>userId는 클라이언트가 보내는 값이 아니라 <b>인증된 JWT principal({@code Long})을 컨트롤러가 서비스에
 * 전달</b>한다 — 소유권 검사·S3 key 유도는 전부 이 값 기준이다.
 */
@RestController
@RequiredArgsConstructor
public class TimelineRecordController implements TimelineRecordApi {

    private final DailyTimelineService dailyTimelineService;
    private final TimelineEventEditService timelineEventEditService;
    private final TimelineDeletionService timelineDeletionService;

    @Override
    public ResponseEntity<ApiResponse<DailyTimelinesResponse>> getDailyTimelines(
            String applicationVersion, Long userId) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getDailyTimelines(applicationVersion, userId)));
    }

    @Override
    public ResponseEntity<ApiResponse<DailyTimelineResponse>> getDailyTimeline(
            String applicationVersion, Long userId, Long dailyRecordId) {
        return ResponseEntity.ok(ApiResponse.success(
                dailyTimelineService.getDailyTimeline(applicationVersion, userId, dailyRecordId)));
    }

    @Override
    public ResponseEntity<ApiResponse<TimelineEventResponse>> updateTimelineEvent(
            String applicationVersion, Long userId, Long timelineEventId, UpdateTimelineEventRequest request) {
        return ResponseEntity.ok(ApiResponse.success(timelineEventEditService.updateEvent(
                applicationVersion, userId, timelineEventId,
                request.eventType(), request.title(), request.subtitle(), request.startAt(), request.endAt())));
    }

    @Override
    public ResponseEntity<ApiResponse<TimelineEventResponse>> updateTimelineEventMemo(
            String applicationVersion, Long userId, Long timelineEventId, UpdateTimelineEventMemoRequest request) {
        return ResponseEntity.ok(ApiResponse.success(timelineEventEditService.updateMemo(
                applicationVersion, userId, timelineEventId, request.memo())));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteTimelineEvent(
            String applicationVersion, Long userId, Long timelineEventId) {
        timelineDeletionService.deleteEvent(applicationVersion, userId, timelineEventId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteDailyRecord(
            String applicationVersion, Long userId, Long dailyRecordId) {
        timelineDeletionService.deleteDailyRecord(applicationVersion, userId, dailyRecordId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
