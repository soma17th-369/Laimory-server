package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI→API {@code POST /s/api/{v}/timeline/drafts/{taskId}/result} 결과 저장 body — 서버간 공개 계약이다.
 *
 * <p>AI가 만든 Event, Event마다의 질문·장소·주소·대표 좌표·장소 후보와 각 Event가 채택한 source의 {@code rawId}를 담는다.
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
            @Schema(description = "장소를 넣지 않는다", example = "점심 식사")
            String title,
            @Schema(description = "장소 자리에 자리 표시 {place}를 쓸 수 있다. 서버는 그대로 저장하고 클라가 표시할 때 "
                    + "현재 place로 채운다. {place}가 있는데 저장될 place가 없으면 결과 전체 400",
                    nullable = true, example = "{place}에서 친구와 파스타")
            String subtitle,
            @Schema(description = "이 Event에 대해 AI가 생성한 질문. 필드 생략과 null 모두 질문 없음이다",
                    nullable = true)
            String question,
            @Schema(description = "AI가 이 Event 단위로 고른 단일 장소명. source item payload의 복수 places와 "
                    + "이름·의미가 다른 Event 결과 필드다. 필드 생략·null·공백은 모두 장소 없음이다. "
                    + "placeCandidates가 1개 이상이면 서버가 1번 후보 이름으로 덮어써 저장한다",
                    nullable = true, example = "성수동 카페")
            String place,
            @Schema(description = "이 Event의 주소 문자열. 필드 생략·null·공백은 모두 주소 없음이다",
                    nullable = true)
            String address,
            @Schema(description = "Event 대표 좌표의 위도 — 사용자가 실제로 있던 관측 위치(이동 Event는 도착지). "
                    + "longitude와 둘 다 보내거나 둘 다 생략한다(한쪽만이면 결과 전체 400). "
                    + "1번 장소 후보에 좌표가 있으면 서버가 그 좌표로 덮어써 저장한다",
                    nullable = true, example = "37.5445")
            Double latitude,
            @Schema(description = "Event 대표 좌표의 경도", nullable = true, example = "127.0559")
            Double longitude,
            @Schema(description = "장소가 애매한 Event의 장소 후보. 배열 순서 = 우선순위. 필드 생략·null·빈 배열은 "
                    + "후보 없음이고, 이름이 공백인 후보는 버린다(나머지 순서 유지)", nullable = true)
            List<PlaceCandidate> placeCandidates,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @Schema(description = "단일 시점 이벤트면 생략(null)", nullable = true)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt,
            @Schema(description = "이 Event가 채택한 source item의 rawId. 최소 1건")
            List<String> sourceRawIds
    ) {
    }

    /** 장소 후보 하나(#612). 좌표는 후보 장소의 위치이며 선택이다(#620). */
    public record PlaceCandidate(
            @Schema(description = "후보 장소명. trim 후 최대 255자", example = "성수동 카페")
            String name,
            @Schema(description = "후보 장소의 위도. longitude와 둘 다 보내거나 둘 다 생략한다. 범위 -90~90. "
                    + "한쪽만·범위 밖·NaN이면 결과 전체 400(이름이 공백이라 버려질 후보도 검사)",
                    nullable = true, example = "37.5447")
            Double latitude,
            @Schema(description = "후보 장소의 경도. 범위 -180~180", nullable = true, example = "127.0561")
            Double longitude
    ) {
    }
}
