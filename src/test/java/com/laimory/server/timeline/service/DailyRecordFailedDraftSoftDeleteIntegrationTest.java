package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AI 실패 정리의 조건부 soft delete(#593)를 실제 MySQL로 검증한다 — 활성 Event 없는 DRAFT만 {@code ai_failed}로
 * 삭제 표시되고, 결과가 들어간 DRAFT·SAVED·이미 삭제된 record는 남는다. 조건이 한 문장의 UPDATE 안의 서브쿼리라
 * 실제 DB 의미로만 확인할 수 있다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class DailyRecordFailedDraftSoftDeleteIntegrationTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(593_001L);

    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        ensureExists(jdbcTemplate, SUBJECT_ID);
    }

    @AfterEach
    void cleanUp() {
        // Event·junction은 FK CASCADE로 record와 함께 지워진다.
        jdbcTemplate.update("DELETE FROM daily_records WHERE subject_id = ?", SUBJECT_ID.toString());
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", SUBJECT_ID.toString());
    }

    @Test
    void emptyDraftIsSoftDeletedAsAiFailed() {
        DailyRecord record = dailyRecordRepository.save(draft(LocalDate.of(2026, 10, 1)));

        int deleted = dailyRecordService.softDeleteFailedDraft(record.getDailyRecordId());

        assertThat(deleted).isEqualTo(1);
        Map<String, Object> row = row(record.getDailyRecordId());
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("modified_by")).isEqualTo("ai_failed");
        assertThat(dailyRecordService.findById(record.getDailyRecordId())).isEmpty();
    }

    @Test
    void draftWithActiveEventIsKept() {
        DailyRecord record = dailyRecordRepository.save(draft(LocalDate.of(2026, 10, 2)));
        timelineEventRepository.save(event(record));

        int deleted = dailyRecordService.softDeleteFailedDraft(record.getDailyRecordId());

        assertThat(deleted).isZero();
        assertThat(row(record.getDailyRecordId()).get("deleted_at")).isNull();
    }

    @Test
    void draftWhoseEventsAreAllSoftDeletedIsSoftDeleted() {
        DailyRecord record = dailyRecordRepository.save(draft(LocalDate.of(2026, 10, 3)));
        TimelineEvent event = timelineEventRepository.save(event(record));
        jdbcTemplate.update("UPDATE timeline_events SET deleted_at = NOW(6) WHERE timeline_event_id = ?",
                event.getTimelineEventId());

        int deleted = dailyRecordService.softDeleteFailedDraft(record.getDailyRecordId());

        assertThat(deleted).isEqualTo(1);
        assertThat(row(record.getDailyRecordId()).get("modified_by")).isEqualTo("ai_failed");
    }

    @Test
    void savedRecordIsKept() {
        DailyRecord record = dailyRecordRepository.save(draft(LocalDate.of(2026, 10, 4)));
        dailyRecordService.markSaved(record.getDailyRecordId(), SUBJECT_ID, EmotionType.HAPPY);

        int deleted = dailyRecordService.softDeleteFailedDraft(record.getDailyRecordId());

        assertThat(deleted).isZero();
        Map<String, Object> row = row(record.getDailyRecordId());
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("status")).isEqualTo("SAVED");
    }

    @Test
    void alreadyDeletedRecordKeepsOriginalDeletion() {
        DailyRecord record = dailyRecordRepository.save(draft(LocalDate.of(2026, 10, 5)));
        LocalDateTime userDeletedAt = LocalDateTime.of(2026, 10, 5, 9, 0);
        // 사용자 삭제와 같은 상태(modified_by NULL) — 삭제 API의 transaction 대신 행을 직접 맞춘다.
        jdbcTemplate.update("UPDATE daily_records SET deleted_at = ? WHERE daily_record_id = ?",
                userDeletedAt, record.getDailyRecordId());

        int deleted = dailyRecordService.softDeleteFailedDraft(record.getDailyRecordId());

        assertThat(deleted).isZero();
        Map<String, Object> row = row(record.getDailyRecordId());
        assertThat(row.get("deleted_at")).isEqualTo(userDeletedAt);
        assertThat(row.get("modified_by")).isNull();
    }

    @Test
    void sameDateDraftIsCreatedAgainAfterSoftDelete() {
        LocalDate recordDate = LocalDate.of(2026, 10, 6);
        DailyRecord failed = dailyRecordRepository.save(draft(recordDate));
        dailyRecordService.softDeleteFailedDraft(failed.getDailyRecordId());

        DailyRecord retried = dailyRecordService.findOrCreateDraft(
                SUBJECT_ID, recordDate, recordDate.atTime(20, 0), "Asia/Seoul");

        assertThat(retried.getDailyRecordId()).isNotEqualTo(failed.getDailyRecordId());
        assertThat(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, recordDate))
                .get()
                .extracting(DailyRecord::getDailyRecordId)
                .isEqualTo(retried.getDailyRecordId());
    }

    private static DailyRecord draft(LocalDate recordDate) {
        return DailyRecord.createDraft(SUBJECT_ID, recordDate, recordDate.atTime(12, 0), "Asia/Seoul");
    }

    private static TimelineEvent event(DailyRecord record) {
        return TimelineEvent.of(record.getDailyRecordId(), TimelineEventType.UNKNOWN,
                record.getRecordDate().atTime(10, 0), null, "산책", null, null, null, null, null, null);
    }

    private Map<String, Object> row(Long dailyRecordId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, deleted_at, modified_by FROM daily_records WHERE daily_record_id = ?", dailyRecordId);
    }
}
