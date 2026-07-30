package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.ItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * API→AI 입력 조회 응답 — AI 추론에 필요한 정규 입력 전부다.
 *
 * <p>JPA entity와 DB 식별자를 노출하지 않는다: {@code userId}·{@code dailyRecordId}·행 PK는 담지 않으며,
 * source는 {@code rawId}로만 식별한다(결과 저장 요청도 같은 축을 쓴다).
 *
 * <p>모든 시각은 record timezone 기준 offset 포함 ISO-8601이다 — staging은 wall-clock으로 저장돼 있어
 * 서버가 {@code recordTimeZone}을 붙여 내보내고, 결과 저장 시 같은 규칙으로 되돌린다.
 *
 */
public record AiTimelineTaskInputResponse(
        String taskId,
        LocalDate recordDate,
        @Schema(example = "Asia/Seoul") String recordTimeZone,
        @Schema(description = "AI가 이번 task에서 이벤트를 만들 시간 범위") Window window,
        List<SourceItem> sourceItems,
        String taskToken
) {

    public record Window(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt
    ) {
    }

    /**
     * 이번 task의 staging source 하나. {@code payload}는 저장된 JSON을 그대로 통과시킨다 —
     * 타입 권위는 payload 밖의 {@code itemType}이다(재매핑 없이 무손실 전달).
     */
    public record SourceItem(
            String rawId,
            ItemType itemType,
            @Schema(description = "시간 미상 아이템이면 생략(null)", nullable = true)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @Schema(nullable = true)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt,
            @Schema(description = "itemType별 payload JSON(서버는 해석 없이 전달)") JsonNode payload
    ) {
    }
}
