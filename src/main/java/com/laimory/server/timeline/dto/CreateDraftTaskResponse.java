package com.laimory.server.timeline.dto;

/** 작성 작업 생성 응답. 클라이언트는 이 taskId로 결과를 폴링한다. */
public record CreateDraftTaskResponse(
        String taskId
) {
}
