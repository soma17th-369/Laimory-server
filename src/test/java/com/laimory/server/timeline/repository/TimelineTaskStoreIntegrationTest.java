package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.NamespacedRedis;
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
    private NamespacedRedis namespacedRedis;

    @Test
    void savesAndFindsTaskFromRealRedis() {
        String taskId = "it-" + UUID.randomUUID();
        try {
            TimelineDraftTask task = TimelineDraftTask.success(LocalDate.of(2026, 5, 8), "token-hash");
            timelineTaskStore.save(taskId, task, Duration.ofMinutes(1));

            Optional<TimelineDraftTask> found = timelineTaskStore.find(taskId);

            assertThat(found).isPresent();
            assertThat(found.get()).isEqualTo(task);
        } finally {
            namespacedRedis.delete("timeline:draft-task:" + taskId);
        }
    }

    @Test
    void findReturnsEmptyForUnknownTask() {
        String unknownTaskId = "it-" + UUID.randomUUID();

        assertThat(timelineTaskStore.find(unknownTaskId)).isEmpty();
    }
}
