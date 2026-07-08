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
        String filename,
        String clientPhotoUri,
        Double latitude,
        Double longitude,
        String description,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "서버 파생(CloudFront 서빙 URL) — 요청 시 무시됨")
        String photoUrl
) implements TimelineItemPayload {
}
