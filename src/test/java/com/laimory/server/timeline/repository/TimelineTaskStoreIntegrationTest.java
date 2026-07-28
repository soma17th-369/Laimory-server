package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
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
                    now, Duration.ofSeconds(90), Duration.ofMinutes(2));
            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.processing(7L, 42L, null, "h",
                            now.minus(Duration.ofSeconds(100))),
                    Duration.ofMinutes(2));

            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(2))).isEqualTo(baseline + 1L);

            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.success(7L, 42L, "h"), Duration.ofHours(24));
            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(2))).isEqualTo(baseline);

            // task key를 일부러 살아 있게 저장해도 startedAt이 TTL 창 밖이면 index 관측에서 제거된다.
            timelineTaskStore.save(expiredId,
                    TimelineDraftTask.processing(7L, 42L, null, "h",
                            now.minus(Duration.ofSeconds(121))),
                    Duration.ofMinutes(2));
            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(2))).isEqualTo(baseline);
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
    void processingIndex_stuckWindowBoundaries_areExactAtMillis() {
        // stuck 창 (now-2m, now-90s] 경계 고정: 89.999s 미포함·90s 포함·119.999s 포함·120s prune.
        Instant now = Instant.now();
        String beforeThresholdId = "it-b1-" + UUID.randomUUID();
        String atThresholdId = "it-b2-" + UUID.randomUUID();
        String beforeExpiryId = "it-b3-" + UUID.randomUUID();
        String atExpiryId = "it-b4-" + UUID.randomUUID();
        List<String> ids = List.of(beforeThresholdId, atThresholdId, beforeExpiryId, atExpiryId);
        try {
            long baseline = timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(2));
            timelineTaskStore.save(beforeThresholdId, TimelineDraftTask.processing(7L, 42L, null, "h",
                    now.minus(Duration.ofMillis(89_999))), Duration.ofMinutes(2));
            timelineTaskStore.save(atThresholdId, TimelineDraftTask.processing(7L, 42L, null, "h",
                    now.minus(Duration.ofMillis(90_000))), Duration.ofMinutes(2));
            timelineTaskStore.save(beforeExpiryId, TimelineDraftTask.processing(7L, 42L, null, "h",
                    now.minus(Duration.ofMillis(119_999))), Duration.ofMinutes(2));
            timelineTaskStore.save(atExpiryId, TimelineDraftTask.processing(7L, 42L, null, "h",
                    now.minus(Duration.ofMillis(120_000))), Duration.ofMinutes(2));

            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(2))).isEqualTo(baseline + 2L);
        } finally {
            for (String id : ids) {
                timelineTaskStore.save(id,
                        TimelineDraftTask.success(7L, 42L, "h"), Duration.ofMinutes(1));
                redisGateway.delete("timeline:draft-task:" + id);
            }
        }
    }

    @Test
    void processingTask_expiresViaTtl_withoutTerminalTransition() throws Exception {
        // PROCESSING 만료는 key 소멸이다(FAILED 전이 없음) — 짧은 test TTL로 만료 의미만 검증한다.
        // production 상수 2m 전달은 TimelineTaskServiceTest가 고정한다(2분 실대기 금지).
        String taskId = "it-expiry-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.processing(7L, 42L, null, "h", Instant.now()),
                    Duration.ofSeconds(1));
            assertThat(timelineTaskStore.find(taskId)).isPresent();

            long deadline = System.currentTimeMillis() + 5_000;
            while (timelineTaskStore.find(taskId).isPresent() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            // key가 사라졌고 어떤 것도 FAILED로 대체 생성하지 않았다.
            assertThat(timelineTaskStore.find(taskId)).isEmpty();
        } finally {
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.success(7L, 42L, "h"), Duration.ofMinutes(1));
            redisGateway.delete("timeline:draft-task:" + taskId);
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

}
