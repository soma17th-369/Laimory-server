package com.laimory.server.common.id;

import java.util.Base64;

/** Redis key/value에서만 쓰는 canonical URL-safe subject 인코딩(16 bytes ↔ 22 chars, padding 없음). */
public final class SubjectIdCodec {

    private static final int ENCODED_LENGTH = 22;

    private SubjectIdCodec() {
    }

    public static String encode(SubjectId subjectId) {
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId is required");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(subjectId.bytes());
    }

    public static SubjectId decode(String encoded) {
        if (encoded == null || encoded.length() != ENCODED_LENGTH) {
            throw new IllegalArgumentException("invalid encoded subject id");
        }
        try {
            SubjectId subjectId = SubjectId.fromBytes(Base64.getUrlDecoder().decode(encoded));
            if (!encode(subjectId).equals(encoded)) {
                throw new IllegalArgumentException("non-canonical encoded subject id");
            }
            return subjectId;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IllegalArgumentException("invalid encoded subject id", e);
        }
    }
}
