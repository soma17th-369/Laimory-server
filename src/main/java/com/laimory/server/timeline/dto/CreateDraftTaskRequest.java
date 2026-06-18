package com.laimory.server.timeline.dto;

import java.time.LocalDate;
import java.util.List;

/** 작성 작업 생성 요청. recordDate와 클라가 1차 정제한 source item 목록을 받는다(userId·emotionType 없음). */
public record CreateDraftTaskRequest(
        LocalDate recordDate,
        List<SourceItemDto> sourceItems
) {
}
