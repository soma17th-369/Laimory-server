package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.PrefixedRedis;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * timeline draft 작업 상태의 Redis 데이터 접근 계층.
 * 논리 키: {@code timeline:draft-task:{taskId}}, 값: TimelineDraftTask JSON.
 * 환경 prefix(dev_ 등) 부착은 {@link PrefixedRedis} facade가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class TimelineTaskStore {

    private static final String KEY_PREFIX = "timeline:draft-task:";
    private static final String TOKEN_USES_KEY_PREFIX = "timeline:callback-token-uses:";

    private final PrefixedRedis redis;
    private final ObjectMapper objectMapper;

    public void save(String taskId, TimelineDraftTask task, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redis.set(KEY_PREFIX + taskId, json, ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TimelineDraftTask 직렬화에 실패했습니다: " + taskId, e);
        }
    }

    /** 콜백 토큰 사용 카운터를 원자적으로 증가시키고 증가 후 값을 반환한다(1 = 최초 사용). */
    public long incrementCallbackTokenUses(String taskId, Duration ttl) {
        return redis.increment(TOKEN_USES_KEY_PREFIX + taskId, ttl);
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
}
