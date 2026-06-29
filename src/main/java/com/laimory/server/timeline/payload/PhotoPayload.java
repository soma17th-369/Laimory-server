package com.laimory.server.timeline.payload;

/**
 * 사진 아이템 payload(DB 저장). 전체 S3 key/서빙 URL이 아니라 최소 식별자 {@code filename}({@code {uuidv7}.{ext}})만
 * 담는다 — full key는 서버가 사용자 id로부터 파생하고, 서빙용 photoUrl은 읽을 때 구성한다(DB 미저장).
 */
public record PhotoPayload(
        String filename,
        Double latitude,
        Double longitude
) implements TimelineItemPayload {
}
