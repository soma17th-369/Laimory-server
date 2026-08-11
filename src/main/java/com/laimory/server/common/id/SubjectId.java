package com.laimory.server.common.id;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/**
 * 콘텐츠 주체(subject)를 식별하는 UUIDv4 기반 불변 value type(#282, 계획 §2.3·§2.4).
 * raw {@code Long userId}나 다른 용도의 UUID와 타입 수준에서 구분해 혼용을 막는다.
 *
 * <p>영속 표현은 canonical 16-byte big-endian({@link #bytes()}) 하나다 — DB {@code BINARY(16)}와 1:1이며
 * hex/base64 문자열 표현은 두지 않는다.
 *
 * <p>{@link #toString()}은 의도적으로 UUID 원문을 노출하지 않는다 — subject는 로그·예외 메시지에
 * 남기지 않는 것이 계획의 불변식이라, 실수로 문자열화되어도 식별자가 새지 않게 한다.
 */
public final class SubjectId {

    private final UUID value;

    private SubjectId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** CSPRNG 기반 새 UUIDv4 subject를 생성한다. */
    public static SubjectId newRandom() {
        return new SubjectId(UUID.randomUUID());
    }

    /**
     * canonical 16-byte 표현으로부터 복원한다(DB {@code BINARY(16)} 조회 경로).
     *
     * @throws IllegalStateException 16바이트가 아니면 — 저장 경로가 항상 16바이트를 쓰므로 위반은
     *                               내부 불변식 위반(손상 데이터)이다.
     */
    public static SubjectId fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalStateException("subject id must be exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        UUID value = new UUID(buffer.getLong(), buffer.getLong());
        if (value.version() != 4 || value.variant() != 2) {
            throw new IllegalStateException("subject id must be an RFC 4122 UUIDv4");
        }
        return new SubjectId(value);
    }

    /** canonical 16-byte big-endian 표현을 반환한다(호출마다 새 배열 — 내부 상태 불변). */
    public byte[] bytes() {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SubjectId other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** UUID 원문을 담지 않는다 — 로그·예외로의 식별자 유출 방지. */
    @Override
    public String toString() {
        return "SubjectId[redacted]";
    }
}
