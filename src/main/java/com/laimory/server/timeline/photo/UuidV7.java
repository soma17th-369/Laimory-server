package com.laimory.server.timeline.photo;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7(시간순 정렬 가능한 UUID) 생성 유틸.
 *
 * <p>상위 48비트에 {@code unix_ts_ms}(밀리초 단위 현재 시각)를 넣어 생성 순서대로 대략 정렬되며, 나머지는
 * 난수로 채운다. 비트 레이아웃은 다음과 같다.
 * <ul>
 *   <li>bits 0..47: {@code unix_ts_ms} (48비트, {@link System#currentTimeMillis()})</li>
 *   <li>bits 48..51: version = {@code 0b0111}(7)</li>
 *   <li>bits 52..63: rand_a (12 난수 비트)</li>
 *   <li>bits 64..65: variant = {@code 0b10}</li>
 *   <li>bits 66..127: rand_b (62 난수 비트)</li>
 * </ul>
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    /**
     * RFC 9562 UUIDv7을 생성한다.
     *
     * @return version 7, variant 2(0b10)를 만족하는 시간순 정렬 가능한 {@link UUID}
     */
    public static UUID randomUuidV7() {
        long unixTsMs = System.currentTimeMillis() & 0xFFFFFFFFFFFFL; // 하위 48비트

        // msb: [48비트 ts][4비트 version=7][12비트 rand_a]
        long randA = RANDOM.nextLong() & 0x0FFFL; // 12비트
        long msb = (unixTsMs << 16) | (0x7L << 12) | randA;

        // lsb: [2비트 variant=0b10][62비트 rand_b]
        long randB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL; // 하위 62비트
        long lsb = (0x2L << 62) | randB;

        return new UUID(msb, lsb);
    }
}
