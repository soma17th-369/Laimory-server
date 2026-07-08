package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 장소(머문 곳) 아이템 payload. 좌표는 필수(지오코딩 enrich 전제).
 *
 * <p>{@code address}/{@code places}는 서버 지오코딩 enrich 필드, {@code durationText}("1시간45분")는
 * 서버가 startAt/endAt로 계산하는 파생 필드다 — 셋 다 요청에 실려와도 무시하고 서버가 재구성 시 채운다(nullable).
 *
 * <p>{@code places}(좌표 주변 장소명, 거리순 — 건물명 역할 포함)는 null=조회 미시도/실패(JSON 키 생략),
 * 빈 배열=정상 조회했으나 주변 장소 없음으로 구분한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationPayload(
        Double latitude,
        Double longitude,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 지오코딩 enrich — 요청 시 무시됨")
        String address,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 지오코딩 enrich(주변 장소명, 거리순) — 요청 시 무시됨")
        List<String> places,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버가 startAt/endAt로 계산 — 요청 시 무시됨")
        String durationText
) implements TimelineItemPayload {
}
