package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 작성 작업 생성 요청. 클라가 1차 정제한 source item 목록과 record_date 계산용 recordAt(벽시계 시각)을 받는다(userId·emotionType 없음).
 *
 * <p>클라는 {@code recordDate}를 직접 보내지 않는다 — 서버가 {@code recordAt}(클라 zone의 벽시계 {@code LocalDateTime})에
 * 정오 경계를 적용해 권위 있게 계산한다. {@code recordTimeZone}은 날짜 계산엔 안 쓰이고 역산(저장된 벽시계 해석)용으로 보존·저장한다.
 */
public record CreateDraftTaskRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T09:00:00",
                description = "기록 기준 시각. **타임존 없는 벽시계 LocalDateTime** — offset이나 'Z'를 붙이면 파싱 실패. "
                        + "서버가 이 값에 정오(12:00) 경계를 적용해 record_date를 계산한다(12시 이전이면 전날로 귀속). recordDate는 클라가 보내지 않는다.")
        LocalDateTime recordAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Asia/Seoul",
                description = "recordAt 벽시계가 속한 타임존(유효한 ZoneId). 날짜 계산엔 안 쓰고 역산용으로 저장한다.")
        String recordTimeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "하루 기록 원천 아이템 목록. 비어 있으면 안 되고, 추가할 신규가 하나도 없으면 ERROR_1013.")
        List<SourceItemDto> sourceItems
) {
}
