package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신 작업의 Redis 데이터 접근 계층.
 * 논리 키: {@code timeline:user-memory-update:{taskId}}, 값: UserMemoryUpdateTask JSON.
 * 환경 prefix 부착은 {@link RedisGateway}가 담당한다.
 *
 * <p><b>key 존재 자체가 진행 중</b>이라 status 필드도, terminal 값을 남기는 저장도 없다 — 종결은 삭제다.
 * 그래서 뒤늦게 도착한 결과나 중복 결과는 자연히 404로 떨어지고, AI는 4xx를 재시도 중단 신호로 읽는다.
 * 단계가 하나뿐이라 index도 CAS도 두지 않는다(draft task와의 차이).
 */
@Component
@RequiredArgsConstructor
public class UserMemoryUpdateTaskStore {

    private static final String KEY_PREFIX = "timeline:user-memory-update:";

    private final RedisGateway redis;
    private final ObjectMapper objectMapper;

    public void save(String taskId, UserMemoryUpdateTask task, Duration ttl) {
        try {
            redis.set(KEY_PREFIX + taskId, objectMapper.writeValueAsString(task), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("UserMemoryUpdateTask 직렬화에 실패했습니다: " + taskId, e);
        }
    }

    public Optional<UserMemoryUpdateTask> find(String taskId) {
        String json = redis.get(KEY_PREFIX + taskId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, UserMemoryUpdateTask.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("UserMemoryUpdateTask 역직렬화에 실패했습니다: " + taskId, e);
        }
    }

    /** 종결. 이후 도착하는 결과는 task 없음(404)이 된다. */
    public void delete(String taskId) {
        redis.delete(KEY_PREFIX + taskId);
    }
}
