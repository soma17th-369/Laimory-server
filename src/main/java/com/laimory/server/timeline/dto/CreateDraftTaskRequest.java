package com.laimory.server.timeline.dto;

import java.time.Instant;
import java.util.List;

/**
 * 작성 작업 생성 요청. 클라가 1차 정제한 source item 목록과 record_date 계산용 anchor를 받는다(userId·emotionType 없음).
 *
 * <p>클라는 {@code recordDate}를 직접 보내지 않는다 — 서버가 {@code recordAnchorAt}(기록 대상 날짜 안의 instant)와
 * {@code recordTimeZone}으로 정오 경계를 적용해 권위 있게 계산한다.
 */
public record CreateDraftTaskRequest(
        Instant recordAnchorAt,
        String recordTimeZone,
        List<SourceItemDto> sourceItems
) {
}
