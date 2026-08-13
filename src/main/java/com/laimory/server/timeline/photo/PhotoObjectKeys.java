package com.laimory.server.timeline.photo;

import com.laimory.server.common.id.UuidV7;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 사진 객체의 파일명/전체 S3 key 생성 유틸.
 *
 * <p>DB에는 파일명({@code uuidv7.jpg})만 저장하고, 전체 S3 key는 UUIDv4 subject의 canonical 16바이트로부터
 * 파생한다. 날짜 폴더는 두지 않는다.
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
     * subject 기반 파일명 → 전체 S3 객체 key. live 경로의 정본이다.
     *
     * @param filename  파일명(예: {@code uuidv7.jpg})
     * @param subjectId 콘텐츠 subject
     * @return {@code {subjectNamespace(subjectId)}/photos/{filename}}
     */
    public static String subjectFullKey(String filename, UUID subjectId) {
        return subjectNamespace(subjectId) + "/photos/" + filename;
    }

    /**
     * subject 기반 namespace — {@code hex(SHA-256(subjectId canonical 16 bytes))}(계획 §2.7).
     *
     * <p>입력은 UUID의 두 64-bit 필드를 big-endian으로 이은 canonical 16바이트다 — 문자열 UUID 표기, context
     * prefix, HMAC lookup key를 입력으로 쓰지 않는다(타입 시그니처가 이를 강제한다).
     *
     * @param subjectId 콘텐츠 subject
     * @return SHA-256 64자 소문자 hex
     */
    public static String subjectNamespace(UUID subjectId) {
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(subjectId.getMostSignificantBits())
                .putLong(subjectId.getLeastSignificantBits())
                .array();
        return sha256hexOf(bytes);
    }

    private static String sha256hexOf(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input)); // 64자 소문자 hex

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
