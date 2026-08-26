package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 사진 아이템 payload(DB 저장).
 *
 * <p>{@code filename}({@code {uuidv7}.{ext}})은 최소 식별자다 — full key는 서버가 사용자 id로부터 파생한다.
 *
 * <p>{@code photoUrl}은 무서명 CloudFront 서빙 URL로, <b>서버 파생 필드다 — 클라가 보낸 값은 무시하고
 * draft 저장 전 enrich 또는 수동 PHOTO writer가 서버 값으로만 구성한다</b>(mass assignment 방어).
 * AI가 DB payload에서 HTTP GET으로 소비하며, 응답에도 저장본 그대로 나간다(읽기 시점 재계산 없음).
 *
 * <p>{@code clientPhotoUri}는 클라이언트 기기의 로컬 사진 URI(예: {@code content://...})다. 서버는 의미를
 * 해석하지 않고 그대로 저장·echo만 한다 — 클라가 타임라인을 받았을 때 다운로드 없이 기기 원본을 즉시 표시(1차
 * 로컬 캐싱)하도록 타임라인 아이템↔로컬 파일 연결고리를 보존하기 위함이다.
 *
 * <p>{@code address}/{@code places}는 서버 지오코딩 enrich 필드다 — 요청에 실려와도 무시하고 draft 저장 전
 * 재구성이 채운다. <b>draft 경로에서만 채워진다</b> — Event PATCH와 수동 Event 생성 POST의 PHOTO
 * 추가는 enrich를 타지 않아 두 필드 없이 저장되므로, 같은 타입에 주소가 있는 사진과 없는 사진이
 * 공존한다. 좌표가 없는 PHOTO도
 * 조회 대상이 아니라 두 필드가 비어 있다({@link StayPayload}와 같은 의미 — key 생략=주소 부재이거나 미연동
 * 이거나 허용된 실패, 빈 배열=장소 없음 또는 허용된 실패).
 *
 * <p>수동 PHOTO 입력은 이 저장 DTO를 request 타입으로 재사용하지 않는다. 클라이언트에서는
 * description/photoUrl을 받지 않고, 저장 시 description은 null, photoUrl은 서버 파생값으로 고정한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhotoPayload(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0190a1b2.jpg",
                description = "사진 업로드 URL 발급 API가 반환한 파일명({uuidv7}.{jpg|png|webp}). 형식이 어긋나면 거절.")
        String filename,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "content://media/external/images/media/1001",
                description = "클라 기기 로컬 사진 URI. 서버는 해석 없이 저장·echo만 한다.")
        String clientPhotoUri,
        @Schema(example = "37.5665", description = "위도(십진도). 선택 — PHOTO는 좌표가 없어도 된다.")
        Double latitude,
        @Schema(example = "126.9780", description = "경도(십진도). 선택.")
        Double longitude,
        @Schema(description = "사진 설명(자유 텍스트). 선택.")
        String description,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 지오코딩 enrich — 요청 시 무시됨")
        String address,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 지오코딩 enrich(주변 장소명, 거리순) — 요청 시 무시됨")
        List<String> places,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 파생(CloudFront 서빙 URL) — 요청 시 무시됨")
        String photoUrl
) implements TimelineItemPayload {
}
