package com.laimory.server.testsupport;

import com.laimory.server.common.id.SubjectId;
import java.nio.ByteBuffer;
import java.util.UUID;

/** 테스트에서 재현 가능한 RFC 4122 UUIDv4 subject를 만드는 fixture. */
public final class TestSubjects {

    private TestSubjects() {
    }

    public static SubjectId id(long seed) {
        UUID uuid = UUID.fromString("00000000-0000-4000-8000-%012x".formatted(seed));
        return SubjectId.fromBytes(ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array());
    }
}
