package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * timeline draft 작업 상태의 Redis 데이터 접근 계층.
 * 논리 키: {@code timeline:draft-task:{taskId}}, 값: TimelineDraftTask JSON.
 * callback token 소비 논리 키: {@code timeline:callback-token-uses:{taskId}}, 값: {@code used}.
 * 환경 prefix(dev_ 등) 부착은 {@link RedisGateway}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class TimelineTaskStore {

    private static final String KEY_PREFIX = "timeline:draft-task:";
    static final String PROCESSING_INDEX_KEY = "timeline:draft-task:processing-index";
    private static final String CALLBACK_TOKEN_USE_KEY_PREFIX = "timeline:callback-token-uses:";
    private static final String CALLBACK_TOKEN_USED_VALUE = "used";

    private final RedisGateway redis;
    private final ObjectMapper objectMapper;

    public void save(String taskId, TimelineDraftTask task, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(task);
            if (task.status() == TaskStatus.PROCESSING) {
                if (task.processingStartedAt() == null) {
                    throw new IllegalStateException("PROCESSING task 시작 시각이 없습니다: " + taskId);
                }
                redis.setAndAddToSortedSet(KEY_PREFIX + taskId, json, ttl,
                        PROCESSING_INDEX_KEY, taskId, task.processingStartedAt().toEpochMilli());
            } else {
                redis.setAndRemoveFromSortedSet(KEY_PREFIX + taskId, json, ttl,
                        PROCESSING_INDEX_KEY, taskId);
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
        try {
            return Optional.of(objectMapper.readValue(json, TimelineDraftTask.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TimelineDraftTask 역직렬화에 실패했습니다: " + taskId, e);
        }
    }

    /**
     * callback token을 task별 marker로 원자 소비한다. true를 받은 요청 하나만 인증 게이트를 통과하며,
     * false는 이미 소비된 token이다. marker에는 raw token이나 hash를 저장하지 않는다.
     */
    public boolean consumeCallbackToken(String taskId, Duration ttl) {
        return redis.setIfAbsent(CALLBACK_TOKEN_USE_KEY_PREFIX + taskId, CALLBACK_TOKEN_USED_VALUE, ttl);
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
