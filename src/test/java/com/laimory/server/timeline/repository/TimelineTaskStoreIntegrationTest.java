package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * TimelineTaskStore ↔ 실 Redis set/get/TTL 왕복 검증.
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineTaskStoreIntegrationTest {

    @Autowired
    private TimelineTaskStore timelineTaskStore;

    @Autowired
    private RedisGateway redisGateway;

    @Test
    void savesAndFindsTaskFromRealRedis() {
        String taskId = "it-" + UUID.randomUUID();
        try {
            TimelineDraftTask task = TimelineDraftTask.success(7L, 42L, "token-hash");
            timelineTaskStore.save(taskId, task, Duration.ofMinutes(1));

            Optional<TimelineDraftTask> found = timelineTaskStore.find(taskId);

            assertThat(found).isPresent();
            assertThat(found.get()).isEqualTo(task);
        } finally {
            redisGateway.delete("timeline:draft-task:" + taskId);
        }
    }

    @Test
    void savesAndFindsProcessingStartedAtFromRealRedis() {
        // 실제 Spring 관리 ObjectMapper 경유로 Instant가 왕복하는지 검증(단위 테스트의 수제 mapper와 별개 고정).
        String taskId = "it-" + UUID.randomUUID();
        try {
            Instant startedAt = Instant.parse("2026-05-08T13:41:07Z");
            TimelineDraftTask task = TimelineDraftTask.processing(7L, 42L, null, "token-hash", startedAt);
            timelineTaskStore.save(taskId, task, Duration.ofMinutes(1));

            Optional<TimelineDraftTask> found = timelineTaskStore.find(taskId);

            assertThat(found).isPresent();
            assertThat(found.get().processingStartedAt()).isEqualTo(startedAt);
        } finally {
            redisGateway.delete("timeline:draft-task:" + taskId);
        }
    }

    @Test
    void terminalTask_hasNoProcessingStartedAt() {
        // terminal(SUCCESS) task는 PROCESSING 시각을 보존하지 않는다(위 savesAndFinds의 success fixture로 확인).
        String taskId = "it-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.success(7L, 42L, "token-hash"), Duration.ofMinutes(1));

            Optional<TimelineDraftTask> found = timelineTaskStore.find(taskId);

            assertThat(found).isPresent();
            assertThat(found.get().processingStartedAt()).isNull();
        } finally {
            redisGateway.delete("timeline:draft-task:" + taskId);
        }
    }

    @Test
    void ownerUserId_roundTripsForAllStates_andLegacyJsonDeserializesToNull() {
        // 실 Redis/Spring ObjectMapper에서 세 상태의 owner 왕복과, owner 없는 legacy JSON의 null 역직렬화를 고정한다.
        String pId = "it-" + UUID.randomUUID();
        String sId = "it-" + UUID.randomUUID();
        String fId = "it-" + UUID.randomUUID();
        String legacyId = "it-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(pId, TimelineDraftTask.processing(7L, 42L, null, "h",
                    Instant.parse("2026-05-08T13:41:07Z")), Duration.ofMinutes(1));
            timelineTaskStore.save(sId, TimelineDraftTask.success(7L, 42L, "h"),
                    Duration.ofMinutes(1));
            timelineTaskStore.save(fId, TimelineDraftTask.failed(7L, 42L, "ERROR_1009", "h"),
                    Duration.ofMinutes(1));
            redisGateway.set("timeline:draft-task:" + legacyId,
                    "{\"status\":\"SUCCESS\",\"recordDate\":\"2026-05-08\",\"callbackTokenHash\":\"h\"}",
                    Duration.ofMinutes(1));

            assertThat(timelineTaskStore.find(pId).orElseThrow().userId()).isEqualTo(7L);
            assertThat(timelineTaskStore.find(sId).orElseThrow().userId()).isEqualTo(7L);
            assertThat(timelineTaskStore.find(fId).orElseThrow().userId()).isEqualTo(7L);
            assertThat(timelineTaskStore.find(legacyId).orElseThrow().userId()).isNull();
        } finally {
            redisGateway.delete("timeline:draft-task:" + pId);
            redisGateway.delete("timeline:draft-task:" + sId);
            redisGateway.delete("timeline:draft-task:" + fId);
            redisGateway.delete("timeline:draft-task:" + legacyId);
        }
    }

    @Test
    void findReturnsEmptyForUnknownTask() {
        String unknownTaskId = "it-" + UUID.randomUUID();

        assertThat(timelineTaskStore.find(unknownTaskId)).isEmpty();
    }

    @Test
    void dateGuard_claimIsExclusive_andCompareOpsRespectHolder() {
        // 실 Redis에서 SET NX 배타성과 Lua compare-refresh/release의 holder 존중을 검증한다(첫 Lua 사용 경로).
        long userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        LocalDate date = LocalDate.of(2026, 5, 8);
        String logicalKey = "timeline:date-guard:" + userId + ":" + date;
        try {
            // 선점은 정확히 한 holder만 성공한다.
            assertThat(timelineTaskStore.claimDateGuard(userId, date, "task:a", Duration.ofMinutes(1))).isTrue();
            assertThat(timelineTaskStore.claimDateGuard(userId, date, "task:b", Duration.ofMinutes(1))).isFalse();

            // holder 불일치 refresh/release는 no-op(false) — 남의 guard를 건드리지 않는다.
            assertThat(timelineTaskStore.refreshDateGuard(userId, date, "task:b", Duration.ofMinutes(1))).isFalse();
            assertThat(timelineTaskStore.releaseDateGuard(userId, date, "task:b")).isFalse();
            assertThat(redisGateway.get(logicalKey)).isEqualTo("task:a");

            // holder 일치 refresh/release는 성공하고, 해제 후에는 새 holder가 선점할 수 있다.
            assertThat(timelineTaskStore.refreshDateGuard(userId, date, "task:a", Duration.ofMinutes(1))).isTrue();
            assertThat(timelineTaskStore.releaseDateGuard(userId, date, "task:a")).isTrue();
            assertThat(redisGateway.get(logicalKey)).isNull();
            assertThat(timelineTaskStore.claimDateGuard(userId, date, "task:b", Duration.ofMinutes(1))).isTrue();
        } finally {
            redisGateway.delete(logicalKey);
        }
    }
}
