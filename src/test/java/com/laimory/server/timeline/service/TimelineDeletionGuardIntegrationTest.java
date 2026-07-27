package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 삭제 ↔ 날짜 guard 직렬화 검증(실 Redis + 실 MySQL): draft(task holder)가 guard를 잡은 동안 삭제는
 * 409(ERROR_1016)로 거절되고 데이터가 보존되며, 삭제가 끝나면 guard가 해제돼 같은 날짜의 새 draft가
 * 즉시 선점할 수 있다. PHOTO item이 없어 S3는 호출되지 않는다(실 AWS 접근 없음).
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineDeletionGuardIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 4);
    private static final String ZONE = "Asia/Seoul";

    @Autowired
    private TimelineDeletionService timelineDeletionService;
    @Autowired
    private TimelineTaskService timelineTaskService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private RedisGateway redisGateway;

    // 다른 테스트·잔여 데이터와 겹치지 않도록 실행마다 임의 사용자로 격리한다(guard 키·record 모두 userId 스코프).
    private long userId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(userId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(
                        TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "이벤트", null))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        dailyRecordRepository.findByUserIdAndRecordDate(userId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
        redisGateway.delete("timeline:date-guard:" + userId + ":" + DATE);
    }

    @Test
    void deleteIsRejectedWhileDraftHoldsGuard_andSucceedsAfterRelease_thenFreesGuardForNewDraft() {
        // 1. draft 작업(task holder)이 guard를 잡은 동안 삭제는 1016으로 거절되고 데이터는 보존된다.
        String taskHolder = TimelineTaskService.taskGuardHolder("it-task");
        assertThat(timelineTaskService.claimDateGuard(userId, DATE, taskHolder)).isTrue();
        assertThatThrownBy(() -> timelineDeletionService.deleteEvent("v1", userId, eventId))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1016));
        assertThat(timelineEventRepository.findById(eventId)).isPresent();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();

        // 2. draft가 terminal로 해제하면 같은 삭제 요청이 즉시 성공한다(재시도 수렴).
        assertThat(timelineTaskService.releaseDateGuard(userId, DATE, taskHolder)).isTrue();
        timelineDeletionService.deleteEvent("v1", userId, eventId);
        assertThat(timelineEventRepository.findById(eventId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent(); // 마지막 Event 삭제 후에도 record 유지

        // 3. 삭제 완료 후 guard는 해제돼 있다 — 같은 날짜의 새 draft가 즉시 선점(생성)할 수 있다.
        String newTaskHolder = TimelineTaskService.taskGuardHolder("it-task-2");
        assertThat(timelineTaskService.claimDateGuard(userId, DATE, newTaskHolder)).isTrue();
        assertThat(timelineTaskService.releaseDateGuard(userId, DATE, newTaskHolder)).isTrue();
    }

    @Test
    void deleteDailyRecord_rejectedWhileGuardHeld_thenDeletesWholeDayAndFreesGuard() {
        String taskHolder = TimelineTaskService.taskGuardHolder("it-task-3");
        assertThat(timelineTaskService.claimDateGuard(userId, DATE, taskHolder)).isTrue();
        assertThatThrownBy(() -> timelineDeletionService.deleteDailyRecord("v1", userId, recordId))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1016));
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();

        assertThat(timelineTaskService.releaseDateGuard(userId, DATE, taskHolder)).isTrue();
        timelineDeletionService.deleteDailyRecord("v1", userId, recordId);
        assertThat(dailyRecordRepository.findById(recordId)).isEmpty();
        assertThat(timelineEventRepository.findById(eventId)).isEmpty(); // events도 cascade 소멸

        assertThat(timelineTaskService.claimDateGuard(userId, DATE,
                TimelineTaskService.taskGuardHolder("it-task-4"))).isTrue();
    }
}
