package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.PrefixedRedis;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
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
    private PrefixedRedis prefixedRedis;

    @Test
    void savesAndFindsTaskFromRealRedis() {
        String taskId = "it-" + UUID.randomUUID();
        try {
            TimelineDraftTask task = TimelineDraftTask.success(LocalDate.of(2026, 5, 8), 42L, "token-hash");
            timelineTaskStore.save(taskId, task, Duration.ofMinutes(1));

            Optional<TimelineDraftTask> found = timelineTaskStore.find(taskId);

            assertThat(found).isPresent();
            assertThat(found.get()).isEqualTo(task);
        } finally {
            prefixedRedis.delete("timeline:draft-task:" + taskId);
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
            assertThat(prefixedRedis.get(logicalKey)).isEqualTo("task:a");

            // holder 일치 refresh/release는 성공하고, 해제 후에는 새 holder가 선점할 수 있다.
            assertThat(timelineTaskStore.refreshDateGuard(userId, date, "task:a", Duration.ofMinutes(1))).isTrue();
            assertThat(timelineTaskStore.releaseDateGuard(userId, date, "task:a")).isTrue();
            assertThat(prefixedRedis.get(logicalKey)).isNull();
            assertThat(timelineTaskStore.claimDateGuard(userId, date, "task:b", Duration.ofMinutes(1))).isTrue();
        } finally {
            prefixedRedis.delete(logicalKey);
        }
    }
}
