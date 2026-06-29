package com.laimory.server.timeline.payload;

/**
 * 사진 아이템 payload(DB 저장).
 *
 * <p>{@code filename}({@code {uuidv7}.{ext}})은 최소 식별자다 — full key는 서버가 사용자 id로부터 파생하고
 * 서빙용 photoUrl은 읽을 때 구성한다(DB 미저장).
 *
 * <p>{@code clientPhotoUri}는 클라이언트 기기의 로컬 사진 URI(예: {@code content://...})다. 서버는 의미를
 * 해석하지 않고 그대로 저장·echo만 한다 — 클라가 타임라인을 받았을 때 다운로드 없이 기기 원본을 즉시 표시(1차
 * 로컬 캐싱)하도록 타임라인 아이템↔로컬 파일 연결고리를 보존하기 위함이다.
 */
public record PhotoPayload(
        String filename,
        String clientPhotoUri,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {
}
