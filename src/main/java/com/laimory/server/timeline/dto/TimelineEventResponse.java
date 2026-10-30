package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventPlaceCandidate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 타임라인 이벤트 응답 DTO(하위 아이템 응답 목록·장소 후보 포함). */
public record TimelineEventResponse(
        Long timelineEventId,
        @Schema(example = "UNKNOWN",
                description = "이벤트 분류. WAKE_UP=기상, SLEEP=수면, MOVEMENT=이동, CALENDAR_EVENT=캘린더 일정, "
                        + "MEAL=식사, PHOTO_MOMENT=사진 기반 순간, MEETING=미팅, CLASS=수업, WORK=업무, "
                        + "EXERCISE=운동, SOCIAL=사회 활동, REST=휴식, STAY=머무름, SHOPPING=쇼핑, "
                        + "CULTURE=문화생활, ERRAND=개인적인 용무, UNKNOWN=분류할 수 없음. "
                        + "UNKNOWN은 분류 미확정 fallback이다(기존 데이터·AI 미판별 포함).")
        TimelineEventType eventType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String title,
        @Schema(description = "장소 자리에 자리 표시 {place}가 들어 있을 수 있다. 서버는 저장한 그대로 내려주며, "
                + "클라가 표시할 때 {place}를 현재 place로 채운다", nullable = true,
                example = "{place}에서 친구와 파스타")
        String subtitle,
        @Schema(description = "AI가 이 이벤트에 대해 생성한 질문. 없으면 null이며 편집 API로 바꿀 수 없다",
                nullable = true)
        String question,
        @Schema(description = "이 이벤트의 장소명. AI가 초기값을 채우고(장소 후보가 있으면 1번 후보) 장소 지정 API로 "
                + "바꿀 수 있다. 없으면 null",
                nullable = true, example = "성수동 카페")
        String place,
        @Schema(description = "이 이벤트의 주소. 없으면 null이며 편집 API로 바꿀 수 없다", nullable = true)
        String address,
        @Schema(description = "이벤트 대표 좌표의 위도. AI가 준 관측 위치(이동은 도착지)가 초기값이고, 사용자가 "
                + "Event 생성·수정·장소 지정에서 좌표를 보내면 그 값으로 바뀐다. 없으면 null이며 longitude와 항상 "
                + "함께 null이거나 함께 값이다",
                nullable = true, example = "37.5445")
        Double latitude,
        @Schema(description = "이벤트 대표 좌표의 경도", nullable = true, example = "127.0559")
        Double longitude,
        @Schema(description = "장소 후보. 배열 순서 = 우선순위이고 없으면 빈 배열이다. 장소를 지정한 뒤·SAVED 날에도 "
                + "그대로 내려간다")
        List<PlaceCandidateResponse> placeCandidates,
        String memo,
        List<TimelineItemResponse> items
) {

    /** {@code placeCandidates}는 우선순위(id) 순서로 넘긴다. */
    public static TimelineEventResponse from(TimelineEvent event, List<TimelineItemResponse> items,
                                             List<TimelineEventPlaceCandidate> placeCandidates) {
        return new TimelineEventResponse(
                event.getTimelineEventId(),
                event.getEventType(),
                event.getStartAt(),
                event.getEndAt(),
                event.getTitle(),
                event.getSubtitle(),
                event.getQuestion(),
                event.getPlace(),
                event.getAddress(),
                event.getLatitude(),
                event.getLongitude(),
                placeCandidates.stream().map(candidate -> new PlaceCandidateResponse(candidate.getName())).toList(),
                event.getMemo(),
                items
        );
    }

    /** 장소 후보 하나. */
    public record PlaceCandidateResponse(
            @Schema(description = "후보 장소명", example = "성수동 카페")
            String name
    ) {
    }
}
