package com.laimory.server.timeline.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 작성 작업 생성 요청. 클라가 1차 정제한 source item 목록과 record_date 계산용 recordAt(벽시계 시각)을 받는다(userId·emotionType 없음).
 *
 * <p>클라는 {@code recordDate}를 직접 보내지 않는다 — 서버가 {@code recordAt}(클라 zone의 벽시계 {@code LocalDateTime})에
 * 정오 경계를 적용해 권위 있게 계산한다. {@code recordTimeZone}은 날짜 계산엔 안 쓰이고 역산(저장된 벽시계 해석)용으로 보존·저장한다.
 */
public record CreateDraftTaskRequest(
        LocalDateTime recordAt,
        String recordTimeZone,
        List<SourceItemDto> sourceItems
) {
}
