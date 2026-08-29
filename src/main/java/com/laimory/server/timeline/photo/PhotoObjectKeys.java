package com.laimory.server.timeline.photo;

import com.laimory.server.common.id.UuidV7;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 사진 객체의 파일명/전체 S3 key 생성 유틸.
 *
 * <p>DB에는 파일명({@code uuidv7.jpg})만 저장하고, 전체 S3 key는 UUIDv4 subject의 canonical 16바이트로부터
 * 파생한다. 날짜 폴더는 두지 않는다.
 */
public final class PhotoObjectKeys {

    /** 저장된 서빙 URL path가 가져야 할 형태 — {@code {sha256 64자}/photos/{filename}}. */
    private static final Pattern SERVING_KEY_PATTERN = Pattern.compile("^[0-9a-f]{64}/photos/([^/]+)$");

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
     * 저장된 서빙 URL에서 full object key를 복원한다. subject를 잃은 행(junction 0)의 유일한 복원 경로다.
     *
     * <p>설정된 CDN 도메인과 대조하지 않고 <b>path만</b> 쓴다 — 도메인이 바뀌어도 저장본의 path는 그대로다.
     * 형태와 filename 형식을 모두 통과한 값만 인정하고, 그 외에는 빈 값을 돌려준다(예외로 흐름을 만들지
     * 않는다). PII redaction이 값을 훼손했던 기간의 행이 여기서 걸러진다(#387 이전 저장분).
     *
     * @param photoUrl 저장된 {@code photoUrl}
     * @return {@code {subjectNamespace}/photos/{filename}} 또는 복원 불가 시 빈 값
     */
    public static Optional<String> objectKeyFromServingUrl(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return Optional.empty();
        }
        String path;
        try {
            path = new URI(photoUrl).getPath();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (path == null || path.length() < 2 || path.charAt(0) != '/') {
            return Optional.empty();
        }
        String objectKey = path.substring(1);
        var matcher = SERVING_KEY_PATTERN.matcher(objectKey);
        if (!matcher.matches() || !PhotoFilenames.isValid(matcher.group(1))) {
            return Optional.empty();
        }
        return Optional.of(objectKey);
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
