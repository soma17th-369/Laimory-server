package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * timeline draft 작업 상태의 Redis 데이터 접근 계층.
 * 논리 키: {@code timeline:draft-task:{taskId}}, 값: TimelineDraftTask JSON.
 * 사용자별 진행 작업 index 논리 키: {@code timeline:draft-task:user:{userId}:processing}
 * (sorted set — member: taskId, score: processingStartedAt epoch ms).
 * 환경 prefix(dev_ 등) 부착은 {@link RedisGateway}가 담당한다.
 *
 * <p><b>불변식:</b> task JSON의 status/owner가 유일한 권위다. 전역/사용자 index는 각각 관측·조회
 * 후보일 뿐이며 단독으로 상태나 응답을 만들지 않는다. PROCESSING 저장은 task JSON+전역 index ZADD+
 * 사용자 index ZADD(+key TTL 갱신)를, terminal 저장은 task JSON+두 index ZREM을 각각 한 Lua 실행
 * 경계로 수행한다 — {@link #save}가 모든 lifecycle 전이의 단일 write 지점이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineTaskStore {

    private static final String KEY_PREFIX = "timeline:draft-task:";
    static final String PROCESSING_INDEX_KEY = "timeline:draft-task:processing-index";
    private static final String USER_PROCESSING_INDEX_KEY_PREFIX = "timeline:draft-task:user:";
    private static final String USER_PROCESSING_INDEX_KEY_SUFFIX = ":processing";
    private final RedisGateway redis;
    private final ObjectMapper objectMapper;

    /** 사용자별 진행 작업 index의 논리 키. owner는 record 계약상 항상 양수라 {@code :null:} 오염이 없다. */
    static String userProcessingIndexKey(long userId) {
        return USER_PROCESSING_INDEX_KEY_PREFIX + userId + USER_PROCESSING_INDEX_KEY_SUFFIX;
    }

    public void save(String taskId, TimelineDraftTask task, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(task);
            String userIndexKey = userProcessingIndexKey(task.userId());
            if (task.status() == TaskStatus.PROCESSING) {
                if (task.processingStartedAt() == null) {
                    throw new IllegalStateException("PROCESSING task 시작 시각이 없습니다: " + taskId);
                }
                // 사용자 index key TTL은 task와 같은 값으로 ZADD마다 갱신된다(gateway PEXPIRE) — 새 task가
                // 추가될 때마다 key deadline이 밀려 어떤 active member보다 먼저 key가 사라지지 않는다.
                redis.setAndAddToSortedSets(KEY_PREFIX + taskId, json, ttl,
                        PROCESSING_INDEX_KEY, userIndexKey, taskId,
                        task.processingStartedAt().toEpochMilli());
            } else {
                redis.setAndRemoveFromSortedSets(KEY_PREFIX + taskId, json, ttl,
                        PROCESSING_INDEX_KEY, userIndexKey, taskId);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TimelineDraftTask 직렬화에 실패했습니다: " + taskId, e);
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
     * 요청 사용자가 소유한 현재 PROCESSING taskId를 생성 최신순(score 내림차순, 동일 ms score는 member
     * 역 lexicographic)으로 반환한다. 사용자 index는 후보일 뿐이고 각 후보의 task JSON을 batch로 읽어
     * status/owner를 검증한다 — missing(만료)·terminal·타인 소유 member는 응답에서 제외하고 요청
     * 사용자의 index에서만 best-effort로 제거한다(제거 실패는 유효 응답을 깨지 않는다).
     *
     * <p>역직렬화 불가 JSON(owner 누락·null·0 포함)은 권위를 판정할 수 없으므로 예외를 전파하고(500)
     * 어떤 member도 자동 삭제하지 않는다(수동 조사 가능성 보존).
     */
    public List<String> findProcessingTaskIds(long userId) {
        String userIndexKey = userProcessingIndexKey(userId);
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
            if (task.userId() != userId || task.status() != TaskStatus.PROCESSING) {
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
        return redis.pruneAndCountSortedSet(PROCESSING_INDEX_KEY,
                nowMillis - processingTtl.toMillis(),
                nowMillis - stuckAfter.toMillis());
    }
}
