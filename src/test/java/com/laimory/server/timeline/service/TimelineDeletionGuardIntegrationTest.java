package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 과거 날짜 guard 키가 남아 있어도 Event·DailyRecord 삭제가 이를 읽거나 지우지 않는지 검증한다. */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineDeletionGuardIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 4);
    private static final String ZONE = "Asia/Seoul";

    @Autowired
    private TimelineDeletionService timelineDeletionService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private RedisGateway redisGateway;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 다른 테스트·잔여 데이터와 겹치지 않도록 실행마다 임의 사용자로 격리한다.
    private UUID subjectId;
    private long legacyUserId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        legacyUserId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        ensureExists(jdbcTemplate, subjectId);
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(subjectId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "이벤트", null, null))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
        // 완료 푸시 경로가 마스터 행을 보정할 수 있어(#314) mapping보다 먼저 지운다(FK RESTRICT).
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
        redisGateway.delete(legacyGuardKey());
    }

    @Test
    void staleGuardKeyDoesNotBlockEventDeleteAndRemainsUntouched() {
        redisGateway.set(legacyGuardKey(), "task:legacy-event", Duration.ofHours(1));

        timelineDeletionService.deleteEvent("v1", subjectId, eventId);

        assertThat(timelineEventRepository.findById(eventId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent(); // 마지막 Event 삭제 후에도 record 유지
        assertThat(redisGateway.get(legacyGuardKey())).isEqualTo("task:legacy-event");
    }

    @Test
    void staleGuardKeyDoesNotBlockDailyRecordDeleteAndRemainsUntouched() {
        redisGateway.set(legacyGuardKey(), "delete:legacy-record", Duration.ofHours(1));

        timelineDeletionService.deleteDailyRecord("v1", subjectId, recordId);

        assertThat(dailyRecordRepository.findById(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(eventId)).isEmpty(); // events도 cascade 소멸
        assertThat(redisGateway.get(legacyGuardKey())).isEqualTo("delete:legacy-record");
    }

    private String legacyGuardKey() {
        return "timeline:date-guard:" + legacyUserId + ":" + DATE;
    }
}
