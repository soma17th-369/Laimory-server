package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.success(7L, 42L, "token-hash"), Duration.ofMinutes(1));
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
    void ownerUserId_roundTripsForAllStates() {
        // 실 Redis/Spring ObjectMapper에서 세 상태의 owner와 numeric error 왕복을 고정한다.
        String pId = "it-" + UUID.randomUUID();
        String sId = "it-" + UUID.randomUUID();
        String fId = "it-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(pId, TimelineDraftTask.processing(7L, 42L, null, "h",
                    Instant.parse("2026-05-08T13:41:07Z")), Duration.ofMinutes(1));
            timelineTaskStore.save(sId, TimelineDraftTask.success(7L, 42L, "h"),
                    Duration.ofMinutes(1));
            timelineTaskStore.save(fId, TimelineDraftTask.failed(7L, 42L, -1009, "h"),
                    Duration.ofMinutes(1));

            assertThat(timelineTaskStore.find(pId).orElseThrow().userId()).isEqualTo(7L);
            assertThat(timelineTaskStore.find(sId).orElseThrow().userId()).isEqualTo(7L);
            assertThat(timelineTaskStore.find(fId).orElseThrow().userId()).isEqualTo(7L);
            assertThat(timelineTaskStore.find(fId).orElseThrow().error()).isEqualTo(-1009);
        } finally {
            timelineTaskStore.save(pId,
                    TimelineDraftTask.success(7L, 42L, "h"), Duration.ofMinutes(1));
            redisGateway.delete("timeline:draft-task:" + pId);
            redisGateway.delete("timeline:draft-task:" + sId);
            redisGateway.delete("timeline:draft-task:" + fId);
        }
    }

    @Test
    void processingIndex_countsStuck_removesTerminal_andPrunesExpired() {
        Instant now = Instant.now();
        String stuckId = "it-stuck-" + UUID.randomUUID();
        String expiredId = "it-expired-" + UUID.randomUUID();
        try {
            long baseline = timelineTaskStore.countStuckProcessing(
                    now, Duration.ofMinutes(10), Duration.ofHours(1));
            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.processing(7L, 42L, null, "h",
                            now.minus(Duration.ofMinutes(11))),
                    Duration.ofHours(1));

            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofMinutes(10), Duration.ofHours(1))).isEqualTo(baseline + 1L);

            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.success(7L, 42L, "h"), Duration.ofHours(24));
            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofMinutes(10), Duration.ofHours(1))).isEqualTo(baseline);

            // task key를 일부러 살아 있게 저장해도 startedAt이 TTL 창 밖이면 index 관측에서 제거된다.
            timelineTaskStore.save(expiredId,
                    TimelineDraftTask.processing(7L, 42L, null, "h",
                            now.minus(Duration.ofMinutes(61))),
                    Duration.ofHours(1));
            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofMinutes(10), Duration.ofHours(1))).isEqualTo(baseline);
        } finally {
            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.success(7L, 42L, "h"), Duration.ofMinutes(1));
            timelineTaskStore.save(expiredId,
                    TimelineDraftTask.success(7L, 42L, "h"), Duration.ofMinutes(1));
            redisGateway.delete("timeline:draft-task:" + stuckId);
            redisGateway.delete("timeline:draft-task:" + expiredId);
        }
    }

    @Test
    void findReturnsEmptyForUnknownTask() {
        String unknownTaskId = "it-" + UUID.randomUUID();

        assertThat(timelineTaskStore.find(unknownTaskId)).isEmpty();
    }

    @Test
    void callbackTokenConsume_isAtomicUnderConcurrentCalls() throws Exception {
        String taskId = "it-token-" + UUID.randomUUID();
        String logicalKey = "timeline:callback-token-uses:" + taskId;
        int contenders = 8;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("callback token consume 경쟁 시작 timeout");
                    }
                    return timelineTaskStore.consumeCallbackToken(taskId, Duration.ofHours(25));
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long winners = 0L;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1L);
            assertThat(redisGateway.get(logicalKey)).isEqualTo("used");
        } finally {
            start.countDown();
            executor.shutdownNow();
            redisGateway.delete(logicalKey);
        }
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
