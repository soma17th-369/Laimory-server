package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;

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

    private DailyRecord record(UUID subjectId, LocalDate recordDate) {
        return DailyRecord.createDraft(subjectId, recordDate, recordDate.atTime(12, 0), "Asia/Seoul");
    }

    private TimelineEvent event(DailyRecord record, int hour, String title) {
        return TimelineEvent.of(record.getDailyRecordId(), TimelineEventType.UNKNOWN,
                LocalDateTime.of(2026, 7, 20, hour, 0), null, title, null, null);
    }

}
