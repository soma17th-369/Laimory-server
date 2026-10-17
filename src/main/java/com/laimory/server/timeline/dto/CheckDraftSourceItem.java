package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 타임라인 생성 가능 확인 요청에 중첩되는 판정 입력 요소. draft 생성 sourceItem에서 판정에 필요한
 * 종류·시각만 받는다 — payload(알림 본문·좌표 등)·rawId는 판정에 쓰지 않아 받지 않는다.
 */
public record CheckDraftSourceItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "STAY", description = "아이템 종류.")
        ItemType itemType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T12:00:00",
                description = "아이템 시작(벽시계 LocalDateTime, offset 없음). 필수.")
        LocalDateTime startAt,
        @Schema(nullable = true, example = "2026-07-08T13:00:00",
                description = "아이템 종료(벽시계 LocalDateTime, offset 없음). 선택 — STAY·MOVEMENT의 위치 시간 계산에만 "
                        + "쓰이며, 없거나 startAt보다 앞서면 0분으로 센다.")
        LocalDateTime endAt
) {
}
