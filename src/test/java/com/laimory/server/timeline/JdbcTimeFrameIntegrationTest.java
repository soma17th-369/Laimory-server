package com.laimory.server.timeline;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC 시간 프레임 비대칭 감지(#371).
 *
 * <p>Hibernate({@code java.sql.Timestamp}) 바인딩은 JVM 기본 존 ↔ URL 선언 존(`serverTimezone`) 차이만큼
 * 저장 리터럴을 밀지만 읽기가 대칭으로 되돌리므로 <b>왕복 테스트는 원리적으로 못 잡는다</b>. 그래서
 * repository로 쓰고 서버측 {@code DATE_FORMAT} 문자열(클라이언트 변환 없음)로 raw 리터럴을 읽어 의도한
 * KST 벽시계와 직접 비교한다. {@code TimeZoneConfig}가 없으면 UTC 기동(CI,
 * {@code TZ=UTC ./gradlew integrationTest})에서 +9h로 실패한다.
 *
 * <p>{@code TimeZone.setDefault}는 JVM 전역·비가역이라 시작 존과 무관하게 결과만 관측한다 — 같은 JVM
 * 안의 테스트 실행 순서에 의존하지 않는다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class JdbcTimeFrameIntegrationTest {

    private static final UUID SUBJECT_ID = id(93_101L);

    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void createSubjectMapping() {
        ensureExists(jdbcTemplate, SUBJECT_ID);
    }

    @Test
    void hibernateWrite_storesIntendedKstWallClockLiteral() {
        DailyRecord saved = dailyRecordRepository.save(DailyRecord.createDraft(
                SUBJECT_ID, LocalDate.of(2026, 5, 8), LocalDateTime.of(2026, 5, 8, 21, 0), "Asia/Seoul"));
        em.flush();

        String literal = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(record_at, '%Y-%m-%d %H:%i:%s') FROM daily_records WHERE daily_record_id = ?",
                String.class, saved.getDailyRecordId());

        assertThat(literal).isEqualTo("2026-05-08 21:00:00");
    }

    @Test
    void hibernateRead_returnsOperationalSqlLiteralVerbatim() {
        // 운영 raw SQL이 넣은 리터럴을 앱이 그대로 읽어야 한다 — 8/21 오발송(21:00 행을 12:00에 due로
        // 판정)의 읽기 방향. 리터럴은 SQL 텍스트로 넣어 클라이언트 변환을 완전히 배제한다.
        DailyRecord saved = dailyRecordRepository.save(DailyRecord.createDraft(
                SUBJECT_ID, LocalDate.of(2026, 5, 9), LocalDateTime.of(2026, 5, 9, 12, 0), "Asia/Seoul"));
        em.flush();
        jdbcTemplate.update("UPDATE daily_records SET record_at = '2026-05-09 21:00:00' WHERE daily_record_id = ?",
                saved.getDailyRecordId());
        em.clear();

        DailyRecord reloaded = dailyRecordRepository.findById(saved.getDailyRecordId()).orElseThrow();

        assertThat(reloaded.getRecordAt()).isEqualTo(LocalDateTime.of(2026, 5, 9, 21, 0));
    }
}
