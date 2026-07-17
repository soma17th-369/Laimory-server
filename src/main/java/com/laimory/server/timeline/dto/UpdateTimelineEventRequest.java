package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 타임라인 Event 수정(PATCH) 요청 — title·subtitle·startAt·endAt 4개 필드를 <b>모두 보내는</b> 절대값 대입 계약.
 *
 * <p>부분 전송이 아니다: 4개 필드가 항상 이 요청의 값으로 교체된다(memo·하위 items는 이 요청으로 바뀌지 않는다).
 * title·startAt은 필수라 {@code null}이면 400이고, subtitle·endAt의 {@code null}은 "비움"이다 —
 * 필드 생략(absent)과 {@code null}을 구분하지 않으므로, 유지하고 싶은 값도 현재 값을 그대로 담아 보내야 한다.
 *
 * <p>시간은 보낸 값 그대로 저장된다 — draft 생성(AI finalize)의 +10분 충돌 보정이나 하위 Item 시간 변경은 없다.
 */
public record UpdateTimelineEventRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "카페에서 휴식",
                description = "이벤트 제목. 앞뒤 공백 제거 후 1~255자 필수 — null이거나 공백뿐이면 400.")
        String title,
        @Schema(example = "성수동 카페거리",
                description = "이벤트 부제목. 앞뒤 공백 제거 후 최대 255자. null이거나 공백뿐이면 비움(null 저장).")
        String subtitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T14:00:00",
                description = "이벤트 시작 시각(타임존 없는 벽시계 LocalDateTime). 필수 — null이면 400. "
                        + "보낸 값 그대로 저장한다(충돌 보정 없음).")
        LocalDateTime startAt,
        @Schema(example = "2026-07-08T15:30:00",
                description = "이벤트 종료 시각. null이면 비움(단일 시점). 값이 있으면 startAt 이상이어야 한다(아니면 400).")
        LocalDateTime endAt
) {
}
