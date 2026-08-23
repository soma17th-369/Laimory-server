package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 두 애플리케이션 인스턴스가 같은 subject guard를 경쟁할 때 Redis SET NX winner가 하나인지 검증한다. */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class UserMemoryUpdateGuardConcurrencyIntegrationTest {

    @Autowired
    private RedisGateway redisGateway;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID subjectId;

    @AfterEach
    void cleanUp() {
        if (subjectId != null) {
            new UserMemoryUpdateTaskStore(redisGateway, objectMapper).releaseGuard(subjectId);
        }
    }

    @Test
    void twoStoreInstancesHaveExactlyOneGuardWinner() throws Exception {
        subjectId = UUID.randomUUID();
        UserMemoryUpdateTaskStore firstInstance = new UserMemoryUpdateTaskStore(redisGateway, objectMapper);
        UserMemoryUpdateTaskStore secondInstance = new UserMemoryUpdateTaskStore(redisGateway, objectMapper);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return firstInstance.acquireGuard(subjectId, "task-first", Duration.ofMinutes(3));
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return secondInstance.acquireGuard(subjectId, "task-second", Duration.ofMinutes(3));
            });
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
    }
}
