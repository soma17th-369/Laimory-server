package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI→API {@code POST /s/api/{v}/timeline/drafts/{taskId}/result} 결과 저장 body — 서버간 공개 계약이다.
 *
 * <p>AI가 만든 Event, Event마다의 질문·장소·주소와 각 Event가 채택한 source의 {@code rawId}를 담는다.
 * confidence·추론 설명 등 현재 DB에 저장하지 않는 AI 내부 출력은 계약에 넣지 않는다. 실패 보고는 이
 * endpoint가 아니라 기존 callback({@code status=FAILED})이 담당하므로 이 body는 항상 결과를 싣는다
 * (조건부 필드 없음).
 *
 * <p>시각은 dispatch window와 같은 offset 포함 ISO-8601이며, 서버가 DailyRecord의 {@code record_timezone}
 * wall-clock으로 정규화해 저장한다(MySQL {@code DATETIME}은 offset을 보존하지 않는다).
 */
public record AiTimelineResultRequest(
        @Schema(description = "AI가 생성한 Event 목록. 최소 1건")
        List<Event> events
) {

    /** 생성할 Event 하나와 그 Event가 채택한 source의 rawId 목록. */
    public record Event(
            @Schema(description = "Event 분류. 판별 불가면 UNKNOWN", example = "MEAL")
            TimelineEventType eventType,
            String title,
            @Schema(nullable = true) String subtitle,
            @Schema(description = "이 Event에 대해 AI가 생성한 질문. 필드 생략과 null 모두 질문 없음이다",
                    nullable = true)
            String question,
            @Schema(description = "AI가 이 Event 단위로 고른 단일 장소명. source item payload의 복수 places와 "
                    + "이름·의미가 다른 Event 결과 필드다. 필드 생략·null·공백은 모두 장소 없음이다",
                    nullable = true, example = "성수동 카페")
            String place,
            @Schema(description = "이 Event의 주소 문자열. 필드 생략·null·공백은 모두 주소 없음이다",
                    nullable = true)
            String address,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @Schema(description = "단일 시점 이벤트면 생략(null)", nullable = true)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt,
            @Schema(description = "이 Event가 채택한 source item의 rawId. 최소 1건")
            List<String> sourceRawIds
    ) {
    }
}
