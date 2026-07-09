package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 사진 아이템 payload(DB 저장).
 *
 * <p>{@code filename}({@code {uuidv7}.{ext}})은 최소 식별자다 — full key는 서버가 사용자 id로부터 파생한다.
 *
 * <p>{@code photoUrl}은 무서명 CloudFront 서빙 URL로, <b>서버 파생 필드다 — 클라가 보낸 값은 무시하고
 * draft 저장 전 enrich 단계가 무조건 덮어쓴다</b>(mass assignment 방어). AI가 DB payload에서 HTTP GET으로
 * 소비하며, 응답에도 저장본 그대로 나간다(읽기 시점 재계산 없음).
 *
 * <p>{@code clientPhotoUri}는 클라이언트 기기의 로컬 사진 URI(예: {@code content://...})다. 서버는 의미를
 * 해석하지 않고 그대로 저장·echo만 한다 — 클라가 타임라인을 받았을 때 다운로드 없이 기기 원본을 즉시 표시(1차
 * 로컬 캐싱)하도록 타임라인 아이템↔로컬 파일 연결고리를 보존하기 위함이다.
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
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 파생(CloudFront 서빙 URL) — 요청 시 무시됨")
        String photoUrl
) implements TimelineItemPayload {
}
