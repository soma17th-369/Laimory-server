package com.laimory.server.user;

import java.util.Arrays;
import java.util.Optional;

/**
 * subject lookup HMAC key의 immutable in-memory snapshot(#282, 계획 §2.9).
 *
 * <p>기동 시 provider가 한 번 만들어 그대로 쓴다 — 요청 경로에서 Secrets Manager를 재호출하지 않는다.
 * rotation 기간에만 previous key가 존재하며(current lookup miss 때 한정 조회), current/previous는
 * 둘 다 32바이트 key와 서로 다른 양의 version을 갖는다.
 *
 * <p>검증 실패는 생성 시점 {@link IllegalStateException}으로 즉시 드러낸다(fail-fast). 예외 메시지에는
 * 어떤 항목이 잘못됐는지 이름만 담고 key 바이트·version 값 원문은 담지 않는다.
 */
public final class SubjectHmacKeySnapshot {

    static final int KEY_LENGTH_BYTES = 32;

    private final short currentVersion;
    private final byte[] currentKey;
    private final Short previousVersion;
    private final byte[] previousKey;

    /** rotation 중이 아닌 평상시 snapshot — current key만 갖는다. */
    public SubjectHmacKeySnapshot(short currentVersion, byte[] currentKey) {
        this(currentVersion, currentKey, null, null);
    }

    /**
     * @param previousVersion rotation 기간에만 — {@code previousKey}와 함께만 존재해야 한다
     * @throws IllegalStateException version이 양수가 아니거나, key가 32바이트가 아니거나,
     *                               previous 쌍이 절반만 있거나, current/previous version이 같으면
     */
    public SubjectHmacKeySnapshot(short currentVersion, byte[] currentKey,
                                  Short previousVersion, byte[] previousKey) {
        if (currentVersion <= 0) {
            throw new IllegalStateException("subject hmac currentVersion must be positive");
        }
        if (currentKey == null || currentKey.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("subject hmac currentKey must be exactly 32 bytes");
        }
        if ((previousVersion == null) != (previousKey == null)) {
            throw new IllegalStateException(
                    "subject hmac previousVersion and previousKey must be present together");
        }
        if (previousVersion != null) {
            if (previousVersion <= 0) {
                throw new IllegalStateException("subject hmac previousVersion must be positive");
            }
            if (previousVersion == currentVersion) {
                throw new IllegalStateException(
                        "subject hmac previousVersion must differ from currentVersion");
            }
            if (previousKey.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException("subject hmac previousKey must be exactly 32 bytes");
            }
            if (Arrays.equals(currentKey, previousKey)) {
                throw new IllegalStateException("subject hmac currentKey and previousKey must differ");
            }
        }
        this.currentVersion = currentVersion;
        this.currentKey = currentKey.clone();
        this.previousVersion = previousVersion;
        this.previousKey = previousKey == null ? null : previousKey.clone();
    }

    public short currentVersion() {
        return currentVersion;
    }

    /** current 32-byte key(호출마다 방어 복사 — snapshot 불변 유지). */
    public byte[] currentKey() {
        return currentKey.clone();
    }

    public boolean hasPreviousKey() {
        return previousKey != null;
    }

    public Optional<Short> previousVersion() {
        return Optional.ofNullable(previousVersion);
    }

    /** rotation 기간의 previous 32-byte key(없으면 empty, 있으면 방어 복사본). */
    public Optional<byte[]> previousKey() {
        return Optional.ofNullable(previousKey).map(byte[]::clone);
    }

    /** key 바이트를 담지 않는다 — 로그·디버그 출력으로의 secret 유출 방지. */
    @Override
    public String toString() {
        return "SubjectHmacKeySnapshot[redacted]";
    }
}
