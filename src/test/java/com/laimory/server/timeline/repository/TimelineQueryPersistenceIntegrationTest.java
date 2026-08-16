package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Timeline 조회 API가 사용하는 사용자 격리·정렬 파생 쿼리를 실제 MySQL로 검증한다. */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class TimelineQueryPersistenceIntegrationTest {

    private static final UUID OWNER_ID = id(9_187_000_001L);
    private static final UUID OTHER_SUBJECT_ID = id(9_187_000_002L);
    private static final UUID EVENT_OWNER_ID = id(9_187_000_003L);

    @Autowired
    private DailyRecordRepository dailyRecordRepository;

    @Autowired
    private TimelineEventRepository timelineEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    @Test
    void dailyRecordQueries_filterByOwnerAndOrderNewestFirst() {
        ensureExists(jdbcTemplate, OWNER_ID);
        ensureExists(jdbcTemplate, OTHER_SUBJECT_ID);
        DailyRecord older = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 7, 20)));
        DailyRecord newer = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 7, 22)));
        DailyRecord other = dailyRecordRepository.save(record(OTHER_SUBJECT_ID, LocalDate.of(2026, 7, 22)));
        Long olderId = older.getDailyRecordId();
        Long newerId = newer.getDailyRecordId();
        Long otherId = other.getDailyRecordId();

        em.flush();
        em.clear();

        assertThat(dailyRecordRepository.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(OWNER_ID))
                .extracting(DailyRecord::getDailyRecordId)
                .containsExactly(newerId, olderId);
        assertThat(dailyRecordRepository.findByDailyRecordIdAndSubjectId(newerId, OWNER_ID))
                .get()
                .extracting(DailyRecord::getDailyRecordId)
                .isEqualTo(newerId);
        assertThat(dailyRecordRepository.findByDailyRecordIdAndSubjectId(otherId, OWNER_ID)).isEmpty();
        assertThat(dailyRecordRepository.findBySubjectIdAndRecordDate(OWNER_ID, LocalDate.of(2026, 7, 22)))
                .get()
                .extracting(DailyRecord::getDailyRecordId)
                .isEqualTo(newerId);
        assertThat(dailyRecordRepository.findBySubjectIdAndRecordDate(OTHER_SUBJECT_ID, LocalDate.of(2026, 7, 22)))
                .get()
                .extracting(DailyRecord::getDailyRecordId)
                .isEqualTo(otherId);
    }

    @Test
    void eventBulkQuery_filtersRequestedRecordsAndUsesStableDisplayOrder() {
        ensureExists(jdbcTemplate, EVENT_OWNER_ID);
        DailyRecord firstRecord = dailyRecordRepository.save(record(EVENT_OWNER_ID, LocalDate.of(2026, 7, 20)));
        DailyRecord secondRecord = dailyRecordRepository.save(record(EVENT_OWNER_ID, LocalDate.of(2026, 7, 21)));
        DailyRecord excludedRecord = dailyRecordRepository.save(record(EVENT_OWNER_ID, LocalDate.of(2026, 7, 22)));

        TimelineEvent firstAtSameTime = timelineEventRepository.save(event(firstRecord, 10, "첫 번째"));
        TimelineEvent secondAtSameTime = timelineEventRepository.save(event(firstRecord, 10, "두 번째"));
        TimelineEvent earlier = timelineEventRepository.save(event(firstRecord, 9, "이른 이벤트"));
        TimelineEvent secondRecordEvent = timelineEventRepository.save(event(secondRecord, 8, "다음 기록"));
        timelineEventRepository.save(event(excludedRecord, 7, "제외"));

        em.flush();
        em.clear();

        assertThat(firstRecord.getDailyRecordId()).isLessThan(secondRecord.getDailyRecordId());
        assertThat(timelineEventRepository
                .findByDailyRecordIdInOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(
                        List.of(secondRecord.getDailyRecordId(), firstRecord.getDailyRecordId())))
                .extracting(TimelineEvent::getTimelineEventId)
                .containsExactly(
                        earlier.getTimelineEventId(),
                        firstAtSameTime.getTimelineEventId(),
                        secondAtSameTime.getTimelineEventId(),
                        secondRecordEvent.getTimelineEventId());
    }

    @Test
    void monthlyRangeQuery_filtersOwnerAndInclusiveBounds_andOrdersByDateAsc() {
        ensureExists(jdbcTemplate, OWNER_ID);
        ensureExists(jdbcTemplate, OTHER_SUBJECT_ID);
        // 전월 말·월 첫날·월 중간·월 말·다음 달 첫날 경계와 타 subject 격리를 함께 검증한다.
        dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 4, 30)));
        DailyRecord firstDay = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 5, 1)));
        DailyRecord middle = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 5, 19)));
        DailyRecord lastDay = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 5, 31)));
        dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 6, 1)));
        dailyRecordRepository.save(record(OTHER_SUBJECT_ID, LocalDate.of(2026, 5, 19)));
        // SAVED + 감정 확정 record도 DRAFT와 함께 조회 대상이다.
        dailyRecordRepository.markSaved(middle.getDailyRecordId(), OWNER_ID, EmotionType.HAPPY,
                LocalDateTime.of(2026, 5, 19, 21, 0));

        em.flush();
        em.clear();

        List<DailyRecord> result = dailyRecordRepository
                .findBySubjectIdAndRecordDateGreaterThanEqualAndRecordDateLessThanEqualOrderByRecordDateAsc(
                        OWNER_ID, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        assertThat(result).extracting(DailyRecord::getDailyRecordId)
                .containsExactly(firstDay.getDailyRecordId(), middle.getDailyRecordId(),
                        lastDay.getDailyRecordId());
        assertThat(result).extracting(DailyRecord::getStatus)
                .containsExactly(DailyRecordStatus.DRAFT, DailyRecordStatus.SAVED, DailyRecordStatus.DRAFT);
        assertThat(result).extracting(DailyRecord::getEmotionType)
                .containsExactly(null, EmotionType.HAPPY, null);
    }

    @Test
    void monthlyRangeQuery_emptyMonth_returnsEmptyList() {
        ensureExists(jdbcTemplate, OWNER_ID);
        dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 4, 30)));

        em.flush();
        em.clear();

        assertThat(dailyRecordRepository
                .findBySubjectIdAndRecordDateGreaterThanEqualAndRecordDateLessThanEqualOrderByRecordDateAsc(
                        OWNER_ID, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
                .isEmpty();
    }

    @Test
    void markSaved_persistsEmotionAndStatusTogether_andRoundTripsEnum() {
        ensureExists(jdbcTemplate, OWNER_ID);
        DailyRecord draft = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 8, 1)));
        DailyRecord untouched = dailyRecordRepository.save(record(OWNER_ID, LocalDate.of(2026, 8, 2)));
        Long savedId = draft.getDailyRecordId();

        int affected = dailyRecordRepository.markSaved(
                savedId, OWNER_ID, EmotionType.HAPPY, LocalDateTime.of(2026, 8, 1, 21, 0));
        em.clear();

        // 감정과 상태가 조건부 UPDATE 하나로 함께 확정되고 기존 emotion_type 컬럼으로 enum이 왕복한다.
        assertThat(affected).isEqualTo(1);
        DailyRecord reloaded = dailyRecordRepository.findById(savedId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(reloaded.getEmotionType()).isEqualTo(EmotionType.HAPPY);
        // null 감정 fixture는 migration 없이 그대로 null로 조회된다(legacy 정상값).
        DailyRecord stillDraft = dailyRecordRepository.findById(untouched.getDailyRecordId()).orElseThrow();
        assertThat(stillDraft.getStatus()).isEqualTo(DailyRecordStatus.DRAFT);
        assertThat(stillDraft.getEmotionType()).isNull();

        // 이미 SAVED인 행은 0행 — 늦은 요청의 감정이 승자를 덮지 않는다.
        int lateAffected = dailyRecordRepository.markSaved(
                savedId, OWNER_ID, EmotionType.VERY_UNHAPPY, LocalDateTime.of(2026, 8, 1, 22, 0));
        em.clear();
        assertThat(lateAffected).isZero();
        assertThat(dailyRecordRepository.findById(savedId).orElseThrow().getEmotionType())
                .isEqualTo(EmotionType.HAPPY);
    }

    private DailyRecord record(UUID subjectId, LocalDate recordDate) {
        return DailyRecord.createDraft(subjectId, recordDate, recordDate.atTime(12, 0), "Asia/Seoul");
    }

    private TimelineEvent event(DailyRecord record, int hour, String title) {
        return TimelineEvent.of(record.getDailyRecordId(), TimelineEventType.UNKNOWN,
                LocalDateTime.of(2026, 7, 20, hour, 0), null, title, null, null);
    }

}
