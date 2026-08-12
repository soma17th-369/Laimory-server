package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA 감사(BaseEntity) 동작 검증.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 audit 컬럼(created_at/updated_at/modified_by)↔DDL 정합을 검증한다.
 * - save 시 created_at/updated_at은 채워지고 modified_by는 NULL
 *   (인증 principal의 auditor 전파 전, AuditorAware가 비어 있음)인지 확인한다.
 * - mutate 시 updated_at은 전진하고 created_at은 불변인지 확인한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class AuditingIntegrationTest {

    private static final SubjectId SUBJECT_ID = id(15L);

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
    void fillsAuditColumnsOnSave() {
        DailyRecord saved = dailyRecordRepository.save(DailyRecord.createDraft(SUBJECT_ID, LocalDate.of(2026, 5, 8), LocalDateTime.of(2026, 5, 8, 12, 0), "Asia/Seoul"));
        em.flush();

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getModifiedBy()).isNull();
    }

    @Test
    void advancesUpdatedAtButKeepsCreatedAtOnMutate() throws InterruptedException {
        DailyRecord saved = dailyRecordRepository.save(DailyRecord.createDraft(SUBJECT_ID, LocalDate.of(2026, 5, 9), LocalDateTime.of(2026, 5, 9, 12, 0), "Asia/Seoul"));
        em.flush();

        LocalDateTime createdAt = saved.getCreatedAt();
        LocalDateTime initialUpdatedAt = saved.getUpdatedAt();

        // 같은 인스턴트로 인한 flakiness 방지 (LastModifiedDate는 풀-precision LocalDateTime.now()).
        Thread.sleep(10);

        // 엔티티는 setter가 없어(팩토리 기반) ReflectionTestUtils로 필드를 dirty 시킨다.
        ReflectionTestUtils.setField(saved, "emotionType", EmotionType.VERY_HAPPY);
        dailyRecordRepository.save(saved);
        em.flush();

        assertThat(saved.getUpdatedAt()).isAfter(initialUpdatedAt);
        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
    }
}
