package com.laimory.server.timeline.photo;

import com.laimory.server.common.id.UuidV7;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 사진 객체의 파일명/전체 S3 key 생성 유틸.
 *
 * <p>DB에는 파일명({@code uuidv7.jpg})만 저장하고, 전체 S3 key는 사용자 식별자로부터 파생한다. key 포맷은
 * 정확히 {@code {sha256hex(userId)}/photos/{filename}}이며 날짜 폴더는 두지 않는다. 사용자 id를 그대로
 * 노출하지 않도록 SHA-256 해시(64자 소문자 hex)로 디렉터리를 만든다.
 */
public final class PhotoObjectKeys {

    /** 허용 사진 content-type → 파일 확장자(단일 기준 — isSupported/extOf가 함께 참조). */
    private static final Map<String, String> EXT_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private PhotoObjectKeys() {
    }

    /** 지원하는 사진 content-type인지 검사한다(서비스 계층의 사전 검증용). */
    public static boolean isSupported(String contentType) {
        return contentType != null && EXT_BY_CONTENT_TYPE.containsKey(contentType);
    }

    /**
     * content-type에 맞는 새 파일명({@code uuidv7.확장자})을 생성한다.
     *
     * @param contentType {@code image/jpeg} / {@code image/png} / {@code image/webp}
     * @return {@code <uuidv7>.jpg|png|webp}
     * @throws IllegalArgumentException null/blank이거나 지원하지 않는 content-type
     */
    public static String newFilename(String contentType) {
        return UuidV7.randomUuidV7() + "." + extOf(contentType);
    }

    /**
     * 파일명과 사용자 id로부터 전체 S3 객체 key를 만든다.
     *
     * @param filename 파일명(예: {@code uuidv7.jpg})
     * @param userId   사용자 id
     * @return {@code {sha256hex(userId)}/photos/{filename}}
     */
    public static String fullKey(String filename, long userId) {
        return sha256hex(userId) + "/photos/" + filename;
    }

    /**
     * 사용자 id의 SHA-256 해시를 64자 소문자 hex로 반환한다.
     *
     * @param userId 사용자 id
     * @return {@code Long.toString(userId)}(UTF-8)의 SHA-256, 64자 소문자 hex
     */
    public static String sha256hex(long userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash); // 64자 소문자 hex

        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 보장하므로 도달하지 않는다.
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    /** content-type → 파일 확장자 매핑. 미지원 타입은 IAE — 서비스 계층 사전 검증(isSupported)의 방어선. */
    private static String extOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("content-type은 필수입니다");
        }
        String ext = EXT_BY_CONTENT_TYPE.get(contentType);
        if (ext == null) {
            throw new IllegalArgumentException("지원하지 않는 content-type: " + contentType);
        }
        return ext;
    }
}
