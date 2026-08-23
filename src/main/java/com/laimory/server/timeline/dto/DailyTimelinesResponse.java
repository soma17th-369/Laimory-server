package com.laimory.server.timeline.dto;

import java.util.List;

/** 인증 사용자의 전체 하루 타임라인 목록. */
public record DailyTimelinesResponse(
        List<DailyTimelineResponse> timelines
) {
}
