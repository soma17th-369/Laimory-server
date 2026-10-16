package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 앱 풀이 내주는 MySQL 연결의 격리 수준이 READ COMMITTED인지 실제 세션 변수로 확인한다(#576).
 * 설정이 빠지거나 값이 틀려도 기동은 실패하지 않고 서버 기본값(RR)으로 조용히 돌아가므로 이 테스트가 회귀를 잡는다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class DatasourceIsolationIntegrationTest {

    private static final String SESSION_ISOLATION = "SELECT @@SESSION.transaction_isolation";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void transactionalConnectionUsesReadCommitted() {
        String isolation = transactionTemplate.execute(
                status -> jdbcTemplate.queryForObject(SESSION_ISOLATION, String.class));

        assertThat(isolation).isEqualTo("READ-COMMITTED");
    }

    @Test
    void autocommitConnectionUsesReadCommitted() {
        String isolation = jdbcTemplate.queryForObject(SESSION_ISOLATION, String.class);

        assertThat(isolation).isEqualTo("READ-COMMITTED");
    }
}
