package com.laimory.server.timeline.dto;

/** AI result 저장 성공 뒤 callback에 사용할 다음 task token. */
public record AiTimelineResultResponse(String taskToken) {
}
