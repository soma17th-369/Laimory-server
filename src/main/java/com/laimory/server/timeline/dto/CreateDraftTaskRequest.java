package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 작성 작업 생성 요청. 클라가 1차 정제한 source item 목록과 함께 기록이 속하는 날({@code recordDate}),
 * 실제 작성 시각({@code recordAt}), AI 이벤트 생성 범위({@code timelineWindow})를 받는다(userId·emotionType 없음).
 *
 * <p>{@code recordDate}·{@code recordAt}·{@code timelineWindow}는 상호 파생 관계가 없는 독립 값이다.
 * 서버는 셋 사이의 날짜 정합성을 검증하지 않는다 — 다음날 아침에 쓰는 어제 일기처럼
 * {@code recordAt}과 {@code recordDate}의 날짜가 다른 것이 정상 계약이다(필드 예시가 그 시나리오다).
 */
public record CreateDraftTaskRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08",
                description = "기록이 속하는 날. **클라이언트 선택 날짜가 단일 권위** — 서버는 계산·보정 없이 "
                        + "Daily Record 조회·생성과 finalize에 그대로 쓴다. 과거·미래 제한 없음.")
        LocalDate recordDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-09T09:12:34",
                description = "사용자가 실제로 기록을 만든 벽시계 시각. **타임존 없는 LocalDateTime** — offset이나 'Z'를 "
                        + "붙이면 파싱 실패. 저장·역산용 메타데이터로, 서버는 이 값에서 아무것도 파생하지 않는다"
                        + "(recordDate와 날짜가 달라도 됨 — 다음날 아침에 쓴 어제 일기).")
        LocalDateTime recordAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Asia/Seoul",
                description = "recordAt 벽시계가 속한 타임존(유효한 ZoneId). 역산용으로 저장한다.")
        String recordTimeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "AI가 이번 요청에서 이벤트를 만들 시간 범위. 서버는 필수값과 startTime < endTime만 "
                        + "검증하고 값 변형 없이 AI에 전달한다. recordDate·recordAt·source item 시각과 독립이다.")
        TimelineWindowDto timelineWindow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "하루 기록 원천 아이템 목록. 비어 있으면 안 되고, 추가할 신규가 하나도 없으면 -1013.")
        List<SourceItemDto> sourceItems
) {
}
