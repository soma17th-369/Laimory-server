package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * timeline draft 작업 상태의 Redis 데이터 접근 계층.
 * 논리 키: {@code timeline:draft-task:{taskId}}, 값: TimelineDraftTask JSON.
 * subject별 진행 작업 index 논리 키:
 * {@code timeline:draft-task:user:{canonicalUuid(subjectId)}:processing}
 * (sorted set — member: taskId, score: processingStartedAt epoch ms).
 * 환경 prefix(dev_ 등) 부착은 {@link RedisGateway}가 담당한다.
 *
 * <p><b>불변식:</b> task JSON의 status/owner가 유일한 권위다. 전역/사용자 index는 각각 관측·조회
 * 후보일 뿐이며 단독으로 상태나 응답을 만들지 않는다. 서버간 stage 전이는 native {@code SET XX KEEPTTL},
 * callback terminal 전이는 native {@code SET XX PX}로 저장한다 — key 존재 여부만 보고 기존 값은
 * 비교하지 않으므로(expected-value CAS 아님) 잘못된 요청 차단은 write 전 token/status/stage 검증이
 * 담당한다. processing index는 최초 PROCESSING 저장과 terminal 전이에서만 native command로 갱신하며,
 * 실패하면 같은 의도의 명령을 한 번 재시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineTaskStore {

    private static final String KEY_PREFIX = "timeline:draft-task:";
    static final String PROCESSING_INDEX_KEY = "timeline:draft-task:processing-index";
    private static final String USER_PROCESSING_INDEX_KEY_PREFIX = "timeline:draft-task:user:";
    private static final String USER_PROCESSING_INDEX_KEY_SUFFIX = ":processing";
    private static final String INDEX_REPAIR_METRIC = "laimory.timeline.task.index.repair";
    private static final String GLOBAL_INDEX = "global";
    private static final String USER_INDEX = "user";
    private final RedisGateway redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** subject별 진행 작업 index의 논리 키. */
    static String subjectProcessingIndexKey(UUID subjectId) {
        return USER_PROCESSING_INDEX_KEY_PREFIX + java.util.Objects.requireNonNull(subjectId, "subjectId")
                + USER_PROCESSING_INDEX_KEY_SUFFIX;
    }

    /**
     * 최초 PROCESSING 생성과 dispatch 확정 실패의 unconditional write 전용이다. PROCESSING이면 두
     * processing index에 최초 등록하고 subject index TTL을 걸며, terminal이면 index에서 제거한다.
     */
    public void save(String taskId, TimelineDraftTask task, Duration ttl) {
        requireProcessingStartedAt(task, taskId);
        redis.set(KEY_PREFIX + taskId, serialize(taskId, task), ttl);
        if (task.status() == TaskStatus.PROCESSING) {
            registerProcessingIndexes(taskId, task, ttl);
        } else {
            removeProcessingIndexes(taskId, task.subjectId());
        }
    }

    public Optional<TimelineDraftTask> find(String taskId) {
        String json = redis.get(KEY_PREFIX + taskId);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(taskId, json));
    }

    /**
     * PROCESSING task 내부 상태(token hash·stage·retry receipt)를 교체한다. native {@code SET XX KEEPTTL}
     * 이라 최초 PROCESSING 저장이 정한 만료 시각을 보존하고 만료된 task를 부활시키지 않는다.
     * processing index는 건드리지 않는다 — 등록은 최초 저장이, 제거는 terminal 전이가 담당한다.
     * 반환 {@code false}는 write 시점에 task key가 없다는 뜻(만료)이다.
     */
    public boolean saveProcessingIfPresent(String taskId, TimelineDraftTask replacement) {
        if (replacement.status() != TaskStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING task만 저장할 수 있습니다: " + taskId);
        }
        requireProcessingStartedAt(replacement, taskId);
        return redis.setIfPresentKeepingTtl(KEY_PREFIX + taskId, serialize(taskId, replacement));
    }

    /**
     * task를 terminal(SUCCESS/FAILED)로 교체한다. native {@code SET XX PX}라 만료된 task를 부활시키지
     * 않고, write가 성공한 뒤에만 두 processing index에서 member를 제거한다.
     * 반환 {@code false}는 write 시점에 task key가 없다는 뜻(만료)이다.
     */
    public boolean saveTerminalIfPresent(String taskId, TimelineDraftTask terminal, Duration ttl) {
        if (terminal.status() == TaskStatus.PROCESSING) {
            throw new IllegalStateException("terminal task만 저장할 수 있습니다: " + taskId);
        }
        if (!redis.setIfPresent(KEY_PREFIX + taskId, serialize(taskId, terminal), ttl)) {
            return false;
        }
        removeProcessingIndexes(taskId, terminal.subjectId());
        return true;
    }

    private static void requireProcessingStartedAt(TimelineDraftTask task, String taskId) {
        if (task.status() == TaskStatus.PROCESSING && task.processingStartedAt() == null) {
            throw new IllegalStateException("PROCESSING task 시작 시각이 없습니다: " + taskId);
        }
    }

    /**
     * 최초 PROCESSING 저장 직후 한 번만 실행한다 — 이후 PROCESSING write는 index를 갱신하지 않으므로
     * subject index TTL도 최초 저장 기준으로 고정된다(같은 subject의 새 task 생성만 TTL을 다시 건다).
     */
    private void registerProcessingIndexes(String taskId, TimelineDraftTask task, Duration ttl) {
        String userIndexKey = subjectProcessingIndexKey(task.subjectId());
        long score = task.processingStartedAt().toEpochMilli();
        runIndexCommand(GLOBAL_INDEX, "add",
                () -> redis.addToSortedSet(PROCESSING_INDEX_KEY, taskId, score));
        runIndexCommand(USER_INDEX, "add",
                () -> redis.addToSortedSet(userIndexKey, taskId, score));
        try {
            if (!redis.expire(userIndexKey, ttl)) {
                repairIndex(USER_INDEX, "expire", () -> readdAndExpire(userIndexKey, taskId, score, ttl), null);
            }
        } catch (RuntimeException primaryFailure) {
            repairIndex(USER_INDEX, "expire",
                    () -> readdAndExpire(userIndexKey, taskId, score, ttl), primaryFailure);
        }
    }

    /** PEXPIRE=false는 subject index key가 사라졌다는 뜻이라 member를 다시 넣은 뒤 TTL을 다시 건다. */
    private void readdAndExpire(String userIndexKey, String taskId, long score, Duration ttl) {
        redis.addToSortedSet(userIndexKey, taskId, score);
        if (!redis.expire(userIndexKey, ttl)) {
            throw new IllegalStateException("Redis user processing index TTL 보정에 실패했습니다");
        }
    }

    private void removeProcessingIndexes(String taskId, UUID subjectId) {
        runIndexCommand(GLOBAL_INDEX, "remove",
                () -> redis.removeFromSortedSet(PROCESSING_INDEX_KEY, List.of(taskId)));
        runIndexCommand(USER_INDEX, "remove",
                () -> redis.removeFromSortedSet(subjectProcessingIndexKey(subjectId), List.of(taskId)));
    }

    private void runIndexCommand(String index, String operation, Runnable command) {
        try {
            command.run();
        } catch (RuntimeException primaryFailure) {
            repairIndex(index, operation, command, primaryFailure);
        }
    }

    /**
     * task를 다시 읽거나 status별 반대 명령으로 뒤집지 않고 원래 의도를 그대로 한 번 재시도한다 —
     * 최초 등록은 AI dispatch 전이라 terminal 전이와 겹치지 않고, ZADD/ZREM은 동일 member 반복이
     * 멱등이다. 두 번째 실패는 task 상태를 rollback하지 않고 metric·warn log로만 관측한다.
     */
    private void repairIndex(String index, String operation, Runnable repair, RuntimeException primaryFailure) {
        try {
            repair.run();
            recordRepair(index, operation, "success");
        } catch (RuntimeException repairFailure) {
            recordRepair(index, operation, "failed");
            log.warn("draft task index repair failed: index={} operation={} primaryFailure={} repairFailure={}",
                    index, operation,
                    primaryFailure == null ? "missing-key" : primaryFailure.getClass().getSimpleName(),
                    repairFailure.getClass().getSimpleName());
        }
    }

    private String serialize(String taskId, TimelineDraftTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TimelineDraftTask 직렬화에 실패했습니다: " + taskId, e);
        }
    }

    private void recordRepair(String index, String operation, String result) {
        Counter.builder(INDEX_REPAIR_METRIC)
                .description("Timeline task processing index repair attempts")
                .tags("index", index, "operation", operation, "result", result)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 요청 사용자가 소유한 현재 PROCESSING taskId를 생성 최신순(score 내림차순, 동일 ms score는 member
     * 역 lexicographic)으로 반환한다. 사용자 index는 후보일 뿐이고 각 후보의 task JSON을 batch로 읽어
     * status/owner를 검증한다 — missing(만료)·terminal·타인 소유 member는 응답에서 제외하고 요청
     * 사용자의 index에서만 best-effort로 제거한다(제거 실패는 유효 응답을 깨지 않는다).
     *
     * <p>역직렬화 불가 JSON(owner 누락·null·0 포함)은 권위를 판정할 수 없으므로 예외를 전파하고(500)
     * 어떤 member도 자동 삭제하지 않는다(수동 조사 가능성 보존).
     */
    public List<String> findProcessingTaskIds(UUID subjectId) {
        String userIndexKey = subjectProcessingIndexKey(subjectId);
        List<String> candidates = redis.getSortedSetReverseRange(userIndexKey);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> jsons = redis.multiGet(candidates.stream().map(taskId -> KEY_PREFIX + taskId).toList());
        List<String> processingTaskIds = new ArrayList<>();
        List<String> staleMembers = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String taskId = candidates.get(i);
            String json = jsons.get(i);
            if (json == null) {
                // task key 만료·소멸 뒤 index에 남은 member.
                staleMembers.add(taskId);
                continue;
            }
            TimelineDraftTask task = deserialize(taskId, json);
            if (!task.subjectId().equals(subjectId) || task.status() != TaskStatus.PROCESSING) {
                // 타인 소유(존재 여부 비노출) 또는 terminal 전이 뒤 잔존 member — 요청 사용자 index만 정리.
                staleMembers.add(taskId);
                continue;
            }
            processingTaskIds.add(taskId);
        }
        pruneStaleMembers(userIndexKey, staleMembers);
        return List.copyOf(processingTaskIds);
    }

    private TimelineDraftTask deserialize(String taskId, String json) {
        try {
            return objectMapper.readValue(json, TimelineDraftTask.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TimelineDraftTask 역직렬화에 실패했습니다: " + taskId, e);
        }
    }

    /**
     * stale member 정리는 best-effort다 — 실패해도 이미 판정한 유효 목록 응답을 깨지 않고 다음
     * GET·terminal 전이가 멱등 ZREM으로 재시도한다. 타인 소유 후보가 섞일 수 있어 taskId 상세는
     * 로그에 남기지 않는다(개수만).
     */
    private void pruneStaleMembers(String userIndexKey, List<String> staleMembers) {
        if (staleMembers.isEmpty()) {
            return;
        }
        try {
            redis.removeFromSortedSet(userIndexKey, staleMembers);
        } catch (RuntimeException e) {
            log.warn("draft task user index stale member cleanup failed: staleCount={}", staleMembers.size(), e);
        }
    }

    /**
     * task TTL 밖의 고아 PROCESSING index를 정리하고, 아직 유효하지만 threshold를 넘긴 작업 수를 센다.
     * task JSON이 권위 원천이며 이 index는 운영 관측만 위한 보조 자료다.
     */
    public long countStuckProcessing(Instant now, Duration stuckAfter, Duration processingTtl) {
        long nowMillis = now.toEpochMilli();
        redis.pruneSortedSetByScore(PROCESSING_INDEX_KEY, nowMillis - processingTtl.toMillis());
        return redis.countSortedSetByScore(PROCESSING_INDEX_KEY, nowMillis - stuckAfter.toMillis());
    }
}
