package com.laimory.server.timeline.photo;

import java.util.regex.Pattern;

/**
 * 사진 {@code filename}({@code {uuidv7}.{ext}}) 형식 검증기.
 *
 * <p>클라이언트가 draft-create나 수동 PHOTO 입력(Event PATCH·Event 생성 POST)으로 보낸 filename은 서버가
 * {@link PhotoObjectKeys#subjectFullKey}에 그대로 끼워 넣으므로, 저장 전에 엄격히 검증해 S3 key 오염을 막는다.
 * 허용 형식은 <b>UUIDv7 + 허용 확장자(jpg/png/webp)</b>뿐이라
 * 슬래시·{@code ..} 같은 경로 조작 문자는 자연히 거부된다(전체 일치 정규식). full key는 항상 server-controlled
 * subject namespace 프리픽스 하위라 타 사용자 네임스페이스 이탈은 불가하지만, 형식 검증으로 fail-fast 한다.
 */
public final class PhotoFilenames {

    /** UUIDv7(version nibble 7, variant 8/9/a/b) + 허용 확장자. 소문자 hex만. */
    private static final Pattern PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private PhotoFilenames() {
    }

    /**
     * filename이 {@code {uuidv7}.{jpg|png|webp}} 형식이 아니면 {@link IllegalArgumentException}(→400)을 던진다.
     */
    public static void requireValid(String filename) {
        if (!isValid(filename)) {
            throw new IllegalArgumentException("invalid photo filename: " + filename);
        }
    }

    /**
     * 예외 없이 형식만 판정한다. 요청 경계가 아니라 <b>저장된 값</b>을 검사하는 곳(orphan 스위퍼의 object
     * key 복원)이 쓴다 — 손상된 저장본은 거절이 아니라 "복원 불가"로 흘려보내야 하기 때문이다.
     */
    public static boolean isValid(String filename) {
        return filename != null && PATTERN.matcher(filename).matches();
    }
}
