package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TaskStatus;

/**
 * 폴링 응답. PROCESSING이면 status만, SUCCESS면 result(그날 타임라인), FAILED면 error를 담는다.
 *
 * <p>{@code error}는 자유 텍스트가 아니라 <b>실패 분류 코드</b>({@code ErrorCode.TASK_FAILURE_CODES}의 이름,
 * 예: {@code "ERROR_1009"})다 — 클라이언트는 {@code header.code}와 같은 사전으로 매핑하고, 미지의 코드는
 * 제네릭 실패로 처리한다(전방 호환).
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
