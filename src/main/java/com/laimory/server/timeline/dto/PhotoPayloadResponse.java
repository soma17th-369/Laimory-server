package com.laimory.server.timeline.dto;

/**
 * 사진 아이템 응답 payload.
 *
 * <p>{@code photoUrl}은 읽을 때 구성하는 무서명 CloudFront 서빙 URL이다(DB 미저장, full key는 사용자 id에서 파생).
 * {@code clientPhotoUri}는 저장된 기기 로컬 URI를 그대로 echo한 값으로, 클라가 다운로드 없이 기기 원본을 즉시
 * 표시(1차 로컬 캐싱)하는 데 쓴다.
 */
public record PhotoPayloadResponse(
        String photoUrl,
        String clientPhotoUri,
        Double latitude,
        Double longitude
) {
}
