package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 타임라인 생성 가능 확인 요청. 클라가 draft 생성에 보낼 sourceItem 목록을 판정에 필요한 필드만 남겨 보낸다.
 */
public record CheckDraftRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "판정할 source item 목록. 빈 배열이면 creatable=false.")
        List<CheckDraftSourceItem> sourceItems
) {
}
