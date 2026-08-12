package com.laimory.server.timeline.photo;

import com.laimory.server.common.id.SubjectId;
import com.laimory.server.common.id.UuidV7;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 사진 객체의 파일명/전체 S3 key 생성 유틸.
 *
 * <p>DB에는 파일명({@code uuidv7.jpg})만 저장하고, 전체 S3 key는 소유자 식별자로부터 파생한다. 날짜 폴더는
 * 두지 않으며 namespace 규칙이 legacy/subject 둘이다(#284, 계획 §2.7).
 *
 * <ul>
 *   <li><b>legacy</b>: {@code {sha256hex(userId)}/photos/{filename}} — 숫자 userId 기반이라 후보 대입이
 *       가능하다. presign/enrich/Event PATCH/cleanup/delete job의 live caller가 #283 activation 전까지
 *       계속 사용한다({@link #fullKey}, {@link #sha256hex}).</li>
 *   <li><b>subject</b>: {@code {hex(SHA-256(subjectId 16 bytes))}/photos/{filename}} — UUIDv4 subject
 *       기반이라 후보 열거가 현실적으로 불가능하다. #283 activation과 migration 도구가 사용한다
 *       ({@link #subjectFullKey}, {@link #subjectNamespace}).</li>
 * </ul>
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
     * <b>legacy</b> — 파일명과 raw 사용자 id로부터 전체 S3 객체 key를 만든다. #283 activation 전까지
     * live caller(presign/enrich/Event PATCH/cleanup/delete job)가 사용하며 동작·포맷은 불변이다.
     *
     * @param filename 파일명(예: {@code uuidv7.jpg})
     * @param userId   사용자 id
     * @return {@code {sha256hex(userId)}/photos/{filename}}
     */
    public static String fullKey(String filename, long userId) {
        return sha256hex(userId) + "/photos/" + filename;
    }

    /**
     * subject 기반 파일명 → 전체 S3 객체 key. legacy {@link #fullKey}와 명시적으로 공존하는 additive
     * 함수다 — live caller 전환은 #283이 한다.
     *
     * @param filename  파일명(예: {@code uuidv7.jpg})
     * @param subjectId 콘텐츠 subject
     * @return {@code {subjectNamespace(subjectId)}/photos/{filename}}
     */
    public static String subjectFullKey(String filename, SubjectId subjectId) {
        return subjectNamespace(subjectId) + "/photos/" + filename;
    }

    /**
     * <b>legacy</b> — raw 사용자 id의 SHA-256 해시를 64자 소문자 hex로 반환한다(legacy namespace).
     *
     * @param userId 사용자 id
     * @return {@code Long.toString(userId)}(UTF-8)의 SHA-256, 64자 소문자 hex
     */
    public static String sha256hex(long userId) {
        return sha256hexOf(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * subject 기반 namespace — {@code hex(SHA-256(subjectId canonical 16 bytes))}(계획 §2.7).
     *
     * <p>입력은 반드시 {@link SubjectId#bytes()} canonical 16바이트다 — 문자열 UUID 표기, context
     * prefix, HMAC lookup key를 입력으로 쓰지 않는다(타입 시그니처가 이를 강제한다).
     *
     * @param subjectId 콘텐츠 subject
     * @return SHA-256 64자 소문자 hex
     */
    public static String subjectNamespace(SubjectId subjectId) {
        return sha256hexOf(subjectId.bytes());
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
