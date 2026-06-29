package com.laimory.server.timeline.dto;

/**
 * 사진 아이템 응답 payload. DB엔 filename만 있으므로, 읽을 때 무서명 CloudFront 서빙 URL({@code photoUrl})을
 * 구성해 내려준다(저장하지 않는다 — full key/URL은 서버가 사용자 id로부터 파생).
 */
public record PhotoPayloadResponse(
        String photoUrl,
        Double latitude,
        Double longitude
) {
}
