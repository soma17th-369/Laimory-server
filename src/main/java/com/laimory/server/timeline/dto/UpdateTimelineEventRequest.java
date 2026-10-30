package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 타임라인 Event 부분 수정 요청. 누락·null은 기존 값 유지이며 subtitle·memo는 blank로 제거한다.
 * <b>예외: endAt은 누락·null 모두 비움(단일 시점)</b>이므로 유지하려면 현재 값을 보내야 한다.
 * photosToAdd는 PHOTO append만 표현하며 누락·null은 빈 목록이다. 좌표(latitude·longitude)는 쌍으로만 교체한다.
 * 길이·공백 정규화는 서비스가, 시간 범위는 transaction 안에서 기존 startAt과 병합한 뒤 검증한다.
 */
public record UpdateTimelineEventRequest(
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "카페에서 휴식",
                description = "이벤트 제목. 누락·null은 유지. 값이 있으면 앞뒤 공백 제거 후 1~255자(공백뿐이면 400).")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "성수동 카페거리",
                description = "이벤트 부제목. 누락·null은 유지, 빈 문자열·공백뿐이면 비움(null 저장). "
                        + "그 외 앞뒤 공백 제거 후 최대 255자.")
        String subtitle,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "2026-07-08T14:00:00",
                description = "이벤트 시작 시각(타임존 없는 벽시계 LocalDateTime). 누락·null은 유지. "
                        + "값이 있으면 보낸 값 그대로 저장한다(충돌 보정 없음).")
        LocalDateTime startAt,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "2026-07-08T15:30:00",
                description = "이벤트 종료 시각. <b>누락·null 모두 비움(단일 시점)</b> — 유지하려면 현재 값을 보낸다. "
                        + "값이 있으면 수정 후 startAt 이상이어야 한다(아니면 400).")
        LocalDateTime endAt,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "MEAL",
                description = "이벤트 분류. 누락·null은 유지. 값이 있으면 허용 literal로 교체한다(UNKNOWN 포함). "
                        + "미지원 literal·숫자 등 비문자열은 400.")
        TimelineEventType eventType,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "이벤트 메모. 누락·null은 유지, 빈 문자열·공백뿐이면 제거, "
                        + "그 외 원문 저장(최대 500자). memo PUT의 null=제거와 다르다.")
        String memo,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "추가할 PHOTO Item 목록. 누락·null·빈 배열은 변경 없음. 기존 Item을 교체하지 않는다.")
        List<UpdateTimelineEventPhotoRequest> photosToAdd,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "37.5445",
                description = "이벤트 대표 좌표의 위도(-90~90). longitude와 둘 다 보내면 좌표를 교체하고, 둘 다 "
                        + "누락·null이면 유지한다. 한쪽만이거나 범위 밖이면 400. 좌표를 지우는 방법은 없다.")
        Double latitude,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "127.0559",
                description = "이벤트 대표 좌표의 경도(-180~180). latitude와 쌍이다.")
        Double longitude
) {
    public UpdateTimelineEventRequest {
        // null 원소는 그대로 보존해 서비스의 PHOTO 입력 검증에서 거절한다.
        photosToAdd = photosToAdd == null ? List.of() : photosToAdd;
    }
}
