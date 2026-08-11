package com.laimory.server.user;

import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * local/test 전용 subject HMAC key provider — {@code app.subject.mode=fixture}에서만 활성화된다.
 *
 * <p>실제 secret이나 별도 fake 알고리즘 대신 <b>같은</b> {@code HmacSHA256} 구현에 deterministic
 * non-production fixture key를 주입한다(계획 §2.9). fixture 기본값은 docker 프로필
 * ({@code application-docker.properties})만 소유하고, 배포 기본 프로필에는 어떤 fixture 기본값도 두지
 * 않는다 — 배포 환경이 fixture로 조용히 뜨면 안 되기 때문이다({@code matchIfMissing} 없음).
 */
@Configuration
@Profile("docker")
@ConditionalOnProperty(name = "app.subject.mode", havingValue = "fixture")
class FixtureSubjectHmacKeyConfig {

    /** fixture snapshot의 고정 version — rotation 개념이 없는 local/test 전용 값. */
    static final short FIXTURE_KEY_VERSION = 1;

    @Bean
    SubjectHmacKeySnapshot subjectHmacKeySnapshot(
            @Value("${app.subject.fixture-key}") String fixtureKeyBase64) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(fixtureKeyBase64);
        } catch (IllegalArgumentException e) {
            // 원문·원인 미포함 — fixture라도 key 문자열을 예외 메시지로 흘리지 않는 규칙을 동일 적용한다.
            throw new IllegalStateException("app.subject.fixture-key must be valid base64");
        }
        return new SubjectHmacKeySnapshot(FIXTURE_KEY_VERSION, key);
    }
}
