package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.TimelineEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 타임라인 이벤트 응답 DTO(하위 아이템 응답 목록 포함). */
public record TimelineEventResponse(
        Long timelineEventId,
        @Schema(example = "UNKNOWN",
                description = "이벤트 분류. WAKE_UP=기상, SLEEP=수면, MOVEMENT=이동, CALENDAR_EVENT=캘린더 일정, "
                        + "MEAL=식사, PHOTO_MOMENT=사진으로 찍은 순간들, MEETING=회의, CLASS=수업, WORK=근무, "
                        + "EXERCISE=운동, SOCIAL=대화, REST=휴식, UNKNOWN=알 수 없음. "
                        + "UNKNOWN은 분류 미확정 fallback이다(기존 데이터·AI 미판별 포함).")
        TimelineEventType eventType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String title,
        String subtitle,
        String memo,
        List<TimelineItemResponse> items
) {

    public static TimelineEventResponse from(TimelineEvent event, List<TimelineItemResponse> items) {
        return new TimelineEventResponse(
                event.getTimelineEventId(),
                event.getEventType(),
                event.getStartAt(),
                event.getEndAt(),
                event.getTitle(),
                event.getSubtitle(),
                event.getMemo(),
                items
        );
    }
}
