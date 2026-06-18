package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TaskStatus;

/**
 * 폴링 응답. PROCESSING이면 status만, SUCCESS면 result(그날 타임라인), FAILED면 error를 담는다.
 */
public record DraftTaskStatusResponse(
        TaskStatus status,
        DailyTimelineResponse result,
        String error
) {

    public static DraftTaskStatusResponse processing() {
        return new DraftTaskStatusResponse(TaskStatus.PROCESSING, null, null);
    }

    public static DraftTaskStatusResponse success(DailyTimelineResponse result) {
        return new DraftTaskStatusResponse(TaskStatus.SUCCESS, result, null);
    }

    public static DraftTaskStatusResponse failed(String error) {
        return new DraftTaskStatusResponse(TaskStatus.FAILED, null, error);
    }
}
