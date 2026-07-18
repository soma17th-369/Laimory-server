package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventMemoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.service.TimelineDeletionService;
import com.laimory.server.timeline.service.TimelineEventEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 확정 타임라인 기록 편집 API 구현. HTTP 문서·계약은 {@link TimelineRecordApi}.
 *
 * <p>userId는 클라이언트가 보내는 값이 아니라 <b>컨트롤러가 현재 사용자 식별자를 결정해 서비스에 전달</b>한다 —
 * 현재는 {@code TimelineDefaults.DEFAULT_USER_ID}, #108 인증 강제 후엔 인증 주체에서 추출한다(이 지점만 교체).
 */
@RestController
@RequiredArgsConstructor
public class TimelineRecordController implements TimelineRecordApi {

    private final TimelineEventEditService timelineEventEditService;
    private final TimelineDeletionService timelineDeletionService;

    @Override
    public ResponseEntity<ApiResponse<TimelineEventResponse>> updateTimelineEvent(
            String applicationVersion, Long timelineEventId, UpdateTimelineEventRequest request) {
        return ResponseEntity.ok(ApiResponse.success(timelineEventEditService.updateEvent(
                applicationVersion, TimelineDefaults.DEFAULT_USER_ID, timelineEventId,
                request.title(), request.subtitle(), request.startAt(), request.endAt())));
    }

    @Override
    public ResponseEntity<ApiResponse<TimelineEventResponse>> updateTimelineEventMemo(
            String applicationVersion, Long timelineEventId, UpdateTimelineEventMemoRequest request) {
        return ResponseEntity.ok(ApiResponse.success(timelineEventEditService.updateMemo(
                applicationVersion, TimelineDefaults.DEFAULT_USER_ID, timelineEventId, request.memo())));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteTimelineEvent(String applicationVersion, Long timelineEventId) {
        timelineDeletionService.deleteEvent(applicationVersion, TimelineDefaults.DEFAULT_USER_ID, timelineEventId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteDailyRecord(String applicationVersion, Long dailyRecordId) {
        timelineDeletionService.deleteDailyRecord(applicationVersion, TimelineDefaults.DEFAULT_USER_ID, dailyRecordId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
