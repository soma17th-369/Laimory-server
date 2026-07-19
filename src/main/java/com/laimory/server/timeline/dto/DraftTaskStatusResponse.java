package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.timeline.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 폴링 응답. PROCESSING이면 status와 elapsedSeconds, SUCCESS면 result(그날 타임라인), FAILED면 error를 담는다.
 *
 * <p>{@code error}는 자유 텍스트가 아니라 <b>실패 분류 코드</b>({@code ErrorCode.TASK_FAILURE_CODES}의 이름,
 * 예: {@code "ERROR_1009"})다 — 클라이언트는 {@code header.code}와 같은 사전으로 매핑하고, 미지의 코드는
 * 제네릭 실패로 처리한다(전방 호환).
 *
 * <p>{@code elapsedSeconds}는 PROCESSING 전용 optional 필드다 — Server가 전처리를 마치고 AI dispatch 대기
 * 단계에 들어간 시각({@code TimelineDraftTask.processingStartedAt}) 이후의 완료된 초. SUCCESS/FAILED와
 * 시각이 없는 legacy PROCESSING task에서는 {@code NON_NULL}로 key 자체를 생략해 기존 terminal shape를
 * 바꾸지 않는다(값을 0으로 위조하지 않음).
 */
public record DraftTaskStatusResponse(
        TaskStatus status,
        DailyTimelineResponse result,
        String error,
        @Schema(description = "AI 작업 대기 경과 시간(완료된 초, Server의 dispatch 대기 시작 기준). "
                + "PROCESSING에서만 제공되는 optional 필드 — SUCCESS/FAILED와 기준 시각이 없는 구버전 작업에서는 "
                + "필드가 생략된다. 값이 있으면 항상 0 이상이다.",
                type = "integer", format = "int64", minimum = "0", example = "12")
        @JsonInclude(JsonInclude.Include.NON_NULL) Long elapsedSeconds
) {

    public static DraftTaskStatusResponse processing(Long elapsedSeconds) {
        return new DraftTaskStatusResponse(TaskStatus.PROCESSING, null, null, elapsedSeconds);
    }

    public static DraftTaskStatusResponse success(DailyTimelineResponse result) {
        return new DraftTaskStatusResponse(TaskStatus.SUCCESS, result, null, null);
    }

    public static DraftTaskStatusResponse failed(String error) {
        return new DraftTaskStatusResponse(TaskStatus.FAILED, null, error, null);
    }
}
