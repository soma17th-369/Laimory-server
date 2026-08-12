package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import static com.laimory.server.testsupport.TaskTokenFixtures.tokenHashes;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.common.id.SubjectId;
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
 *
 * <p>사용자별 진행 작업 index 테스트는 테스트마다 무작위 userId로 격리하고, fixture가 만든 task key·
 * 전역/사용자 index member만 finally에서 제거한다(공유 Redis의 다른 key는 삭제하지 않음).
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineTaskStoreIntegrationTest {

    @Autowired
    private TimelineTaskStore timelineTaskStore;

    @Autowired
    private RedisGateway redisGateway;

    @Autowired
    private ObjectMapper objectMapper;

    private static final SubjectId SUBJECT = id(7L);

    private static SubjectId uniqueSubjectId() {
        return SubjectId.newRandom();
    }

    /** terminal 저장(전역·사용자 index ZREM)을 거쳐 task key와 사용자 index key를 제거한다. */
    private void cleanupTask(SubjectId subjectId, String taskId) {
        timelineTaskStore.save(taskId, TimelineDraftTask.success(subjectId, 42L, tokenHashes("h")), Duration.ofMinutes(1));
        redisGateway.delete("timeline:draft-task:" + taskId);
        redisGateway.delete(TimelineTaskStore.subjectProcessingIndexKey(subjectId));
    }

    @Test
    void savesAndFindsTaskFromRealRedis() {
        String taskId = "it-" + UUID.randomUUID();
        try {
            TimelineDraftTask task = TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("token-hash"));
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
            TimelineDraftTask task = TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("token-hash"), startedAt);
            timelineTaskStore.save(taskId, task, Duration.ofMinutes(1));

            Optional<TimelineDraftTask> found = timelineTaskStore.find(taskId);

            assertThat(found).isPresent();
            assertThat(found.get().processingStartedAt()).isEqualTo(startedAt);
        } finally {
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("token-hash")), Duration.ofMinutes(1));
            redisGateway.delete("timeline:draft-task:" + taskId);
        }
    }

    @Test
    void terminalTask_hasNoProcessingStartedAt() {
        // terminal(SUCCESS) task는 PROCESSING 시각을 보존하지 않는다(위 savesAndFinds의 success fixture로 확인).
        String taskId = "it-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("token-hash")), Duration.ofMinutes(1));

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
            timelineTaskStore.save(pId, TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                    Instant.parse("2026-05-08T13:41:07Z")), Duration.ofMinutes(1));
            timelineTaskStore.save(sId, TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")),
                    Duration.ofMinutes(1));
            timelineTaskStore.save(fId, TimelineDraftTask.failed(SUBJECT, 42L, -1009, tokenHashes("h")),
                    Duration.ofMinutes(1));

            assertThat(timelineTaskStore.find(pId).orElseThrow().subjectId()).isEqualTo(SUBJECT);
            assertThat(timelineTaskStore.find(sId).orElseThrow().subjectId()).isEqualTo(SUBJECT);
            assertThat(timelineTaskStore.find(fId).orElseThrow().subjectId()).isEqualTo(SUBJECT);
            assertThat(timelineTaskStore.find(fId).orElseThrow().error()).isEqualTo(-1009);
        } finally {
            timelineTaskStore.save(pId,
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofMinutes(1));
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
                    now, Duration.ofSeconds(90), Duration.ofMinutes(3));
            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                            now.minus(Duration.ofSeconds(100))),
                    Duration.ofMinutes(3));

            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(3))).isEqualTo(baseline + 1L);

            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofHours(24));
            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(3))).isEqualTo(baseline);

            // task key를 일부러 살아 있게 저장해도 startedAt이 TTL 창 밖이면 index 관측에서 제거된다.
            timelineTaskStore.save(expiredId,
                    TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                            now.minus(Duration.ofSeconds(181))),
                    Duration.ofMinutes(3));
            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(3))).isEqualTo(baseline);
        } finally {
            timelineTaskStore.save(stuckId,
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofMinutes(1));
            timelineTaskStore.save(expiredId,
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofMinutes(1));
            redisGateway.delete("timeline:draft-task:" + stuckId);
            redisGateway.delete("timeline:draft-task:" + expiredId);
        }
    }

    @Test
    void processingIndex_stuckWindowBoundaries_areExactAtMillis() {
        // stuck 창 (now-3m, now-90s] 경계 고정: 89.999s 미포함·90s 포함·179.999s 포함·180s prune.
        Instant now = Instant.now();
        String beforeThresholdId = "it-b1-" + UUID.randomUUID();
        String atThresholdId = "it-b2-" + UUID.randomUUID();
        String beforeExpiryId = "it-b3-" + UUID.randomUUID();
        String atExpiryId = "it-b4-" + UUID.randomUUID();
        List<String> ids = List.of(beforeThresholdId, atThresholdId, beforeExpiryId, atExpiryId);
        try {
            long baseline = timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(3));
            timelineTaskStore.save(beforeThresholdId, TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofMillis(89_999))), Duration.ofMinutes(3));
            timelineTaskStore.save(atThresholdId, TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofMillis(90_000))), Duration.ofMinutes(3));
            timelineTaskStore.save(beforeExpiryId, TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofMillis(179_999))), Duration.ofMinutes(3));
            timelineTaskStore.save(atExpiryId, TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofMillis(180_000))), Duration.ofMinutes(3));

            assertThat(timelineTaskStore.countStuckProcessing(
                    now, Duration.ofSeconds(90), Duration.ofMinutes(3))).isEqualTo(baseline + 2L);
        } finally {
            for (String id : ids) {
                timelineTaskStore.save(id,
                        TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofMinutes(1));
                redisGateway.delete("timeline:draft-task:" + id);
            }
        }
    }

    @Test
    void processingTask_expiresViaTtl_withoutTerminalTransition() throws Exception {
        // PROCESSING 만료는 key 소멸이다(FAILED 전이 없음) — 짧은 test TTL로 만료 의미만 검증한다.
        // production 상수 3m 전달은 TimelineTaskServiceTest가 고정한다(3분 실대기 금지).
        String taskId = "it-expiry-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(taskId,
                    TimelineDraftTask.processing(SUBJECT, 42L, null, tokenHashes("h"), Instant.now()),
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
                    TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofMinutes(1));
            redisGateway.delete("timeline:draft-task:" + taskId);
        }
    }

    @Test
    void findReturnsEmptyForUnknownTask() {
        String unknownTaskId = "it-" + UUID.randomUUID();

        assertThat(timelineTaskStore.find(unknownTaskId)).isEmpty();
    }

    @Test
    void userIndex_returnsOwnedProcessingNewestFirst_andIsolatesUsers() {
        // T1·T5·T6: 같은 사용자의 복수 task는 덮어쓰지 않고 전부 score(processingStartedAt) 내림차순으로
        // 반환하며, 다른 사용자의 유효 PROCESSING task는 절대 섞이지 않는다(키 자체가 사용자별).
        SubjectId userA = uniqueSubjectId();
        SubjectId userB = uniqueSubjectId();
        Instant now = Instant.now();
        String a1 = "it-user-" + UUID.randomUUID();
        String a2 = "it-user-" + UUID.randomUUID();
        String a3 = "it-user-" + UUID.randomUUID();
        String b1 = "it-user-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(a1, TimelineDraftTask.processing(userA, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofSeconds(20))), Duration.ofMinutes(3));
            timelineTaskStore.save(a2, TimelineDraftTask.processing(userA, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofSeconds(10))), Duration.ofMinutes(3));
            timelineTaskStore.save(a3, TimelineDraftTask.processing(userA, 42L, null, tokenHashes("h"), now),
                    Duration.ofMinutes(3));
            timelineTaskStore.save(b1, TimelineDraftTask.processing(userB, 43L, null, tokenHashes("h"), now),
                    Duration.ofMinutes(3));

            assertThat(timelineTaskStore.findProcessingTaskIds(userA)).containsExactly(a3, a2, a1);
            assertThat(timelineTaskStore.findProcessingTaskIds(userB)).containsExactly(b1);
        } finally {
            cleanupTask(userA, a1);
            cleanupTask(userA, a2);
            cleanupTask(userA, a3);
            cleanupTask(userB, b1);
        }
    }

    @Test
    void userIndex_sameMillisecondScore_tieBreaksByMemberReverseLexicographic() {
        // T7: UUIDv7 동일 ms 영역은 생성 순서를 보장하지 않으므로 엄격한 intra-ms 순서는 계약하지 않고,
        // Redis reverse range의 member 역 lexicographic 순서만 deterministic 계약으로 고정한다.
        SubjectId user = uniqueSubjectId();
        Instant sameInstant = Instant.now();
        String base = "it-tie-" + UUID.randomUUID();
        String lower = base + "-a";
        String higher = base + "-b";
        try {
            timelineTaskStore.save(lower, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"), sameInstant),
                    Duration.ofMinutes(3));
            timelineTaskStore.save(higher, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"), sameInstant),
                    Duration.ofMinutes(3));

            assertThat(timelineTaskStore.findProcessingTaskIds(user)).containsExactly(higher, lower);
        } finally {
            cleanupTask(user, lower);
            cleanupTask(user, higher);
        }
    }

    @Test
    void userIndex_terminalTransition_removesMemberAtomically_andListExcludes() {
        // T9·T14·T18b: markSuccess/markFailed가 공유하는 terminal 저장 경계 한 번이 사용자 index member도
        // 제거해 이후 목록에서 즉시 사라진다(SUCCESS/FAILED 종류 무관). 마지막 member 제거로 빈 sorted set
        // key는 Redis가 없앤다.
        SubjectId user = uniqueSubjectId();
        Instant now = Instant.now();
        String p1 = "it-terminal-" + UUID.randomUUID();
        String p2 = "it-terminal-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(p1, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"),
                    now.minus(Duration.ofSeconds(5))), Duration.ofMinutes(3));
            timelineTaskStore.save(p2, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"), now),
                    Duration.ofMinutes(3));
            assertThat(timelineTaskStore.findProcessingTaskIds(user)).containsExactly(p2, p1);

            timelineTaskStore.save(p1, TimelineDraftTask.success(user, 42L, tokenHashes("h")), Duration.ofMinutes(1));
            assertThat(timelineTaskStore.findProcessingTaskIds(user)).containsExactly(p2);

            timelineTaskStore.save(p2, TimelineDraftTask.failed(user, 42L, -1009, tokenHashes("h")), Duration.ofMinutes(1));
            assertThat(timelineTaskStore.findProcessingTaskIds(user)).isEmpty();
            assertThat(redisGateway.getSortedSetReverseRange(
                    TimelineTaskStore.subjectProcessingIndexKey(user))).isEmpty();
        } finally {
            cleanupTask(user, p1);
            cleanupTask(user, p2);
        }
    }

    @Test
    void userIndex_expiredTaskMember_isExcludedAndPruned() throws Exception {
        // T8·T17·T18a: 짧은 TTL로 task key만 자연 만료시키면(만료는 key 소멸 — terminal 전이 아님) 남은
        // index member는 응답에서 제외되고 같은 조회가 lazy prune한다. 아직 유효한 task는 계속 재발견된다.
        SubjectId user = uniqueSubjectId();
        Instant now = Instant.now();
        String shortLived = "it-expired-" + UUID.randomUUID();
        String alive = "it-alive-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(shortLived, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"), now),
                    Duration.ofSeconds(1));
            // 이후 저장의 TTL(1m)이 사용자 index key TTL을 갱신해 shortLived 만료 뒤에도 key가 살아 있다.
            timelineTaskStore.save(alive, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"),
                    now.plusMillis(10)), Duration.ofMinutes(1));

            long deadline = System.currentTimeMillis() + 5_000;
            while (timelineTaskStore.find(shortLived).isPresent() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(timelineTaskStore.find(shortLived)).isEmpty();

            assertThat(timelineTaskStore.findProcessingTaskIds(user)).containsExactly(alive);
            // stale member는 같은 조회에서 제거됐다 — 유효한 alive member만 남는다.
            assertThat(redisGateway.getSortedSetReverseRange(
                    TimelineTaskStore.subjectProcessingIndexKey(user))).containsExactly(alive);
        } finally {
            cleanupTask(user, shortLived);
            cleanupTask(user, alive);
        }
    }

    @Test
    void userIndex_terminalJsonWithLeftoverMember_isExcludedAndPruned() throws Exception {
        // T18c(권위 JSON이 terminal인 경우)·T9: 3-key Lua 밖에서 task JSON만 terminal로 바뀐 부분 실패/
        // legacy 상황을 시뮬레이션한다 — 목록은 index가 아니라 JSON 권위를 따라 제외하고 member를 정리한다.
        SubjectId user = uniqueSubjectId();
        String taskId = "it-leftover-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(taskId, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"), Instant.now()),
                    Duration.ofMinutes(3));
            redisGateway.set("timeline:draft-task:" + taskId,
                    objectMapper.writeValueAsString(TimelineDraftTask.success(user, 42L, tokenHashes("h"))),
                    Duration.ofMinutes(1));

            assertThat(timelineTaskStore.findProcessingTaskIds(user)).isEmpty();
            assertThat(redisGateway.getSortedSetReverseRange(
                    TimelineTaskStore.subjectProcessingIndexKey(user))).isEmpty();
        } finally {
            cleanupTask(user, taskId);
        }
    }

    @Test
    void userIndex_wrongOwnerMember_isExcludedAndPrunedFromRequesterIndexOnly() {
        // T4·T5: 요청 사용자 index에 타인 소유 task member가 섞여도(오염 시뮬레이션) 존재 여부를 노출하지
        // 않고 제외하며, 요청 사용자의 잘못된 member만 정리한다 — 소유자의 index·task JSON은 그대로다.
        SubjectId userA = uniqueSubjectId();
        SubjectId userB = uniqueSubjectId();
        Instant now = Instant.now();
        String a1 = "it-owner-" + UUID.randomUUID();
        String b1 = "it-owner-" + UUID.randomUUID();
        String dummyValueKey = "timeline:draft-task:it-dummy-" + UUID.randomUUID();
        String userAIndexKey = TimelineTaskStore.subjectProcessingIndexKey(userA);
        try {
            timelineTaskStore.save(a1, TimelineDraftTask.processing(userA, 42L, null, tokenHashes("h"), now),
                    Duration.ofMinutes(3));
            timelineTaskStore.save(b1, TimelineDraftTask.processing(userB, 43L, null, tokenHashes("h"), now),
                    Duration.ofMinutes(3));
            // 오염 주입: b1 member를 userA index에만 추가한다(b1 task JSON·userB index는 건드리지 않음).
            redisGateway.setAndAddToSortedSets(dummyValueKey, "x", Duration.ofMinutes(3),
                    userAIndexKey, userAIndexKey, b1, now.plusMillis(10).toEpochMilli());

            assertThat(timelineTaskStore.findProcessingTaskIds(userA)).containsExactly(a1);
            assertThat(redisGateway.getSortedSetReverseRange(userAIndexKey)).containsExactly(a1);
            // 소유자 쪽은 영향이 없다 — b1은 계속 userB에서만 재발견된다.
            assertThat(timelineTaskStore.findProcessingTaskIds(userB)).containsExactly(b1);
        } finally {
            redisGateway.delete(dummyValueKey);
            cleanupTask(userA, a1);
            cleanupTask(userB, b1);
        }
    }

    @Test
    void userIndex_newTaskRefreshesKeyTtl_andKeyExpiresAfterInactivity() throws Exception {
        // T20(D11): 사용자 index key TTL은 PROCESSING 저장마다 task와 같은 값으로 갱신된다 — 갱신이 없으면
        // 첫 task TTL에 key가 통째로 사라져 이후 task까지 유실된다. 마지막 생성 뒤 TTL 동안 새 생성이
        // 없으면 key 전체가 자연 소멸한다(production 3m 상수 전달은 TimelineTaskServiceTest가 고정 —
        // 여기는 short-TTL analog로 만료 의미만 검증한다).
        SubjectId user = uniqueSubjectId();
        Duration shortTtl = Duration.ofSeconds(3);
        String userIndexKey = TimelineTaskStore.subjectProcessingIndexKey(user);
        Instant t0 = Instant.now();
        String first = "it-ttl-" + UUID.randomUUID();
        String second = "it-ttl-" + UUID.randomUUID();
        try {
            timelineTaskStore.save(first, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"), t0), shortTtl);
            Thread.sleep(1_500);
            timelineTaskStore.save(second, TimelineDraftTask.processing(user, 42L, null, tokenHashes("h"),
                    t0.plusMillis(1_500)), shortTtl);

            // first task key 만료를 기다린다(만료 시점: t0+3s — second와 index key는 t0+4.5s까지 유효).
            long deadline = System.currentTimeMillis() + 6_000;
            while (timelineTaskStore.find(first).isPresent() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(timelineTaskStore.find(first)).isEmpty();

            // second 저장이 key TTL을 갱신하지 않았다면 index key가 이미 소멸해 빈 목록이 나온다.
            // first stale member는 이 조회가 제외·prune하고 second는 재발견된다.
            assertThat(timelineTaskStore.findProcessingTaskIds(user)).containsExactly(second);

            // 위 조회는 유효한 second member를 정리하지 않았다 — 이후 GET 없이 member가 사라지는 유일한
            // 경로는 key 단위 만료다. 마지막 생성(t0+1.5s) 기준 TTL(3s)이 지나면 key가 통째로 소멸한다.
            deadline = System.currentTimeMillis() + 6_000;
            while (!redisGateway.getSortedSetReverseRange(userIndexKey).isEmpty()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(redisGateway.getSortedSetReverseRange(userIndexKey)).isEmpty();
        } finally {
            cleanupTask(user, first);
            cleanupTask(user, second);
        }
    }

    @Test
    void userIndex_concurrentCreateTerminalAndList_returnsOnlyAllowedSnapshots() throws Exception {
        // T15·T16: create/terminal 저장과 GET이 경쟁해도 반환 목록은 D13이 허용한 snapshot 중 하나다 —
        // 모든 원소는 이 사용자의 실제 생성 task이고 타인 task·발명된 ID·index-only 부분 결과가 없으며,
        // 상대 순서는 항상 score 내림차순과 일치한다. 최종 상태는 결정적이다(생성 후 전부, 종결 후 빈 목록).
        SubjectId userA = uniqueSubjectId();
        SubjectId userB = uniqueSubjectId();
        Instant base = Instant.now();
        String foreign = "it-race-foreign-" + UUID.randomUUID();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add("it-race-" + i + "-" + UUID.randomUUID());
        }
        List<String> newestFirst = new ArrayList<>(created);
        java.util.Collections.reverse(newestFirst);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            timelineTaskStore.save(foreign, TimelineDraftTask.processing(userB, 43L, null, tokenHashes("h"), base),
                    Duration.ofMinutes(3));

            List<List<String>> snapshots = java.util.Collections.synchronizedList(new ArrayList<>());

            // phase 1 — create 경쟁: 최종 상태는 결정적으로 "전부, 최신순"이다.
            runWithConcurrentReads(executor, snapshots, userA, () -> {
                for (int i = 0; i < created.size(); i++) {
                    timelineTaskStore.save(created.get(i), TimelineDraftTask.processing(
                            userA, 42L, null, tokenHashes("h"), base.plusMillis(i * 10L)), Duration.ofMinutes(3));
                    Thread.sleep(20);
                }
            });
            assertThat(timelineTaskStore.findProcessingTaskIds(userA)).isEqualTo(newestFirst);

            // phase 2 — terminal 경쟁: 최종 상태는 결정적으로 빈 목록이다.
            runWithConcurrentReads(executor, snapshots, userA, () -> {
                for (String taskId : created) {
                    timelineTaskStore.save(taskId, TimelineDraftTask.success(userA, 42L, tokenHashes("h")),
                            Duration.ofMinutes(1));
                    Thread.sleep(20);
                }
            });
            assertThat(timelineTaskStore.findProcessingTaskIds(userA)).isEmpty();

            assertThat(snapshots).isNotEmpty();
            for (List<String> snapshot : snapshots) {
                // 발명된 ID·타인 task 없음(index-only 부분/오염 결과 금지).
                assertThat(created).containsAll(snapshot);
                assertThat(snapshot).doesNotContain(foreign);
                // 관측 시점과 무관하게 상대 순서는 score 내림차순 그대로다.
                assertThat(snapshot).isEqualTo(
                        newestFirst.stream().filter(snapshot::contains).toList());
            }
            // 타 사용자 재발견은 경쟁과 무관하게 그대로 유효하다.
            assertThat(timelineTaskStore.findProcessingTaskIds(userB)).containsExactly(foreign);
        } finally {
            executor.shutdownNow();
            for (String taskId : created) {
                cleanupTask(userA, taskId);
            }
            cleanupTask(userB, foreign);
        }
    }

    /** writer 작업이 도는 동안 같은 사용자 목록 조회를 반복해 관측 snapshot을 수집한다. */
    private void runWithConcurrentReads(ExecutorService executor, List<List<String>> snapshots,
                                        SubjectId subjectId, ThrowingWriter writer) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean writing = new java.util.concurrent.atomic.AtomicBoolean(true);
        Future<?> reads = executor.submit(() -> {
            while (writing.get()) {
                snapshots.add(timelineTaskStore.findProcessingTaskIds(subjectId));
                Thread.sleep(5);
            }
            return null;
        });
        Future<?> writes = executor.submit(() -> {
            try {
                writer.run();
                return null;
            } finally {
                writing.set(false);
            }
        });
        writes.get(30, TimeUnit.SECONDS);
        reads.get(30, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingWriter {
        void run() throws Exception;
    }

}
