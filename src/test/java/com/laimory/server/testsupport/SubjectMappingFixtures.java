package com.laimory.server.testsupport;

import com.laimory.server.common.id.SubjectId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실 DB owner FK 테스트용 subject mapping fixture. 호출한 테스트 트랜잭션과 함께 rollback된다. */
public final class SubjectMappingFixtures {

    private SubjectMappingFixtures() {
    }

    public static void ensureExists(JdbcTemplate jdbcTemplate, SubjectId subjectId) {
        jdbcTemplate.update("INSERT IGNORE INTO user_subject_links "
                        + "(user_lookup_key, subject_id, lookup_key_version) VALUES (?, ?, 1)",
                sha256(subjectId.bytes()), subjectId.bytes());
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
