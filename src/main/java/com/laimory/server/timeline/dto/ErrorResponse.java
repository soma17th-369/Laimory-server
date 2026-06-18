package com.laimory.server.timeline.dto;

/** 단순 에러 응답 바디(메시지 1개). */
public record ErrorResponse(
        String message
) {
}
