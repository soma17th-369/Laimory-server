package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.RedisGateway;
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

    // 다른 테스트·잔여 데이터와 겹치지 않도록 실행마다 임의 사용자로 격리한다.
    private long userId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(userId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "이벤트", null, null))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        dailyRecordRepository.findByUserIdAndRecordDate(userId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
        redisGateway.delete(legacyGuardKey());
    }

    @Test
    void staleGuardKeyDoesNotBlockEventDeleteAndRemainsUntouched() {
        redisGateway.set(legacyGuardKey(), "task:legacy-event", Duration.ofHours(1));

        timelineDeletionService.deleteEvent("v1", userId, eventId);

        assertThat(timelineEventRepository.findById(eventId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent(); // 마지막 Event 삭제 후에도 record 유지
        assertThat(redisGateway.get(legacyGuardKey())).isEqualTo("task:legacy-event");
    }

    @Test
    void staleGuardKeyDoesNotBlockDailyRecordDeleteAndRemainsUntouched() {
        redisGateway.set(legacyGuardKey(), "delete:legacy-record", Duration.ofHours(1));

        timelineDeletionService.deleteDailyRecord("v1", userId, recordId);

        assertThat(dailyRecordRepository.findById(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(eventId)).isEmpty(); // events도 cascade 소멸
        assertThat(redisGateway.get(legacyGuardKey())).isEqualTo("delete:legacy-record");
    }

    private String legacyGuardKey() {
        return "timeline:date-guard:" + userId + ":" + DATE;
    }
}
