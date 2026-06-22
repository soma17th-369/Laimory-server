package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.ModifiedByType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA 감사(BaseEntity) 동작 검증.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 audit 컬럼(created_at/updated_at/modified_by_type)↔DDL 정합을 검증한다.
 * - save 시 audit 3컬럼이 채워지고 modified_by_type=OPERATION(인증 도입 전 상수)인지 확인한다.
 * - mutate 시 updated_at은 전진하고 created_at은 불변인지 확인한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class AuditingIntegrationTest {

    @Autowired
    private DailyRecordRepository dailyRecordRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void fillsAuditColumnsOnSave() {
        DailyRecord saved = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 8)));
        em.flush();

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getModifiedByType()).isNotNull();
        assertThat(saved.getModifiedByType()).isEqualTo(ModifiedByType.OPERATION);
    }

    @Test
    void advancesUpdatedAtButKeepsCreatedAtOnMutate() throws InterruptedException {
        DailyRecord saved = dailyRecordRepository.save(DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 9)));
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
