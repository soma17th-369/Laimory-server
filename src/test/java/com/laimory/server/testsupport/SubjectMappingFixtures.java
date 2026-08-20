package com.laimory.server.testsupport;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실 DB owner FK 테스트용 subject mapping fixture. 호출한 테스트 트랜잭션과 함께 rollback된다. */
public final class SubjectMappingFixtures {

    private SubjectMappingFixtures() {
    }

    public static void ensureExists(JdbcTemplate jdbcTemplate, UUID subjectId) {
        jdbcTemplate.update("INSERT IGNORE INTO user_subject_links "
                        + "(user_lookup_key, subject_id, lookup_key_version) VALUES (?, ?, 1)",
                sha256(ByteBuffer.allocate(16)
                        .putLong(subjectId.getMostSignificantBits())
                        .putLong(subjectId.getLeastSignificantBits())
                        .array()),
                subjectId.toString());
    }

    /**
     * subject를 owner로 갖는 푸시 설정 행을 FK 순서대로 지운다(#314).
     * {@code user_subject_links} 행을 지우는 정리 코드는 이 helper를 먼저 호출해야 한다 —
     * 두 테이블 모두 ON DELETE RESTRICT라 mapping 삭제가 막힌다(운영 탈퇴 경로와 같은 순서).
     */
    public static void deleteSubjectScopedPushRows(JdbcTemplate jdbcTemplate, UUID subjectId) {
        jdbcTemplate.update("DELETE FROM scheduled_notification_preferences WHERE subject_id = ?",
                subjectId.toString());
        jdbcTemplate.update("DELETE FROM subject_preferences WHERE subject_id = ?", subjectId.toString());
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
