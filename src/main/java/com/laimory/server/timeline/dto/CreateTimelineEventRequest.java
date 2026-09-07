package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 타임라인 Event 수동 생성 요청. eventType·title·startAt의 필수값은 Bean Validation이,
 * 길이·공백 정규화·시간 범위·사진 규칙은 서비스가 검증한다.
 * subtitle·endAt·memo는 누락과 null 모두 비움이며, photosToAdd는 빈 목록으로 정규화한다.
 */
public record CreateTimelineEventRequest(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "REST",
                description = "이벤트 분류. 누락·null·미지원 literal·숫자 등 비문자열은 400. UNKNOWN 포함.")
        TimelineEventType eventType,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "카페에서 휴식",
                description = "이벤트 제목. 앞뒤 공백 제거 후 1~255자 필수 — 누락·null·공백뿐이면 400.")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "성수동",
                description = "이벤트 부제목. 누락·null·공백뿐이면 비움(null 저장). 그 외 앞뒤 공백 제거 후 최대 255자.")
        String subtitle,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T14:00:00",
                description = "이벤트 시작 시각(타임존 없는 벽시계 LocalDateTime). 누락·null은 400. "
                        + "보낸 값 그대로 저장한다(AI 결과 저장의 +10분 충돌 보정 없음).")
        LocalDateTime startAt,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "2026-07-08T15:00:00",
                description = "이벤트 종료 시각. 누락·null이면 비움(단일 시점). 값이 있으면 startAt 이상이어야 한다.")
        LocalDateTime endAt,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "이벤트 메모. 누락·null·공백뿐이면 메모 없음. 그 외 원문을 저장한다(최대 500자).")
        String memo,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "함께 생성·연결할 PHOTO Item 목록. 누락·null·빈 배열은 사진 없음. "
                        + "검증·중복·개수·오류 규칙은 Event PATCH photosToAdd와 동일하며, "
                        + "클라이언트가 presign·S3 업로드 성공을 확인한 뒤 보낸다.")
        List<UpdateTimelineEventPhotoRequest> photosToAdd
) {
    public CreateTimelineEventRequest {
        // null 원소는 그대로 보존해 서비스의 PHOTO 입력 검증에서 거절한다.
        photosToAdd = photosToAdd == null ? List.of() : photosToAdd;
    }
}
