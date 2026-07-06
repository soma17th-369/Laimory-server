package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 이동의 출발({@code start})/도착({@code end}) 끝점. {@link MovementPayload}에 중첩된다(독립 payload 타입 아님).
 *
 * <p>{@code latitude}/{@code longitude}는 클라 제공(필수), {@code address}/{@code places}는
 * 서버 지오코딩 enrich 필드다 — 요청에 실려와도 무시하고 서버가 재구성 시 채운다(nullable).
 *
 * <p>{@code places}(좌표 주변 장소명, 거리순 — 건물명 역할 포함)는 null=조회 미시도/실패(JSON 키 생략),
 * 빈 배열=정상 조회했으나 주변 장소 없음으로 구분한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementEndpoint(
        Double latitude,
        Double longitude,
        String address,
        List<String> places
) {
}
