package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.util.UUID;

/**
 * 선생성 commit을 마친 draft task의 접수 요청(#515). token은 싣지 않는다 — 접수 경로가 접수 직전에 만든다.
 *
 * @param timelineWindow Redis task에 보존할 local 원본 window
 * @param offsetWindow   AI 접수 body에 실을 offset window(POST 시점의 record timezone으로 변환한 스냅샷)
 */
public record TimelineDispatchCommand(String taskId, UUID subjectId, long dailyRecordId,
                                      TimelineDraftTask.TimelineWindow timelineWindow,
                                      AiTimelineDispatchRequest.Window offsetWindow) {
}
