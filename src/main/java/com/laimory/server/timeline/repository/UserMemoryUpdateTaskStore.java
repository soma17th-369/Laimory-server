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
 * User Memory 갱신 <b>진행 상태</b>의 Redis 데이터 접근 계층 — 작업 하나와 사용자 guard를 함께 다룬다.
 * 논리 키: 작업이 {@code timeline:user-memory-update:{taskId}}(값 UserMemoryUpdateTask JSON),
 * guard가 {@code timeline:user-memory-update:user:{userId}}(SET NX, TTL).
 * 환경 prefix 부착은 {@link RedisGateway}가 담당한다.
 *
 * <p><b>key 존재 자체가 진행 중</b>이라 status 필드도, terminal 값을 남기는 저장도 없다 — 종결은 삭제다.
 * 그래서 뒤늦게 도착한 결과나 중복 결과는 자연히 404로 떨어지고, AI는 4xx를 재시도 중단 신호로 읽는다.
 * 단계가 하나뿐이라 index도 CAS도 두지 않는다(draft task와의 차이).
 *
 * <p><b>guard가 미반영 큐가 아니라 여기 있는 이유</b>: guard가 답하는 질문은 "이 사용자의 갱신이 지금
 * 진행 중인가"이고, 그건 큐("아직 반영 안 된 날이 무엇인가")가 아니라 작업의 상태다. TTL도 task와 같은
 * 값을 공유한다 — 소유자가 죽어도 둘이 같이 풀려야 다음 갱신이 막히지 않는다.
 */
@Component
@RequiredArgsConstructor
public class UserMemoryUpdateTaskStore {

    private static final String KEY_PREFIX = "timeline:user-memory-update:";
    private static final String GUARD_KEY_PREFIX = KEY_PREFIX + "user:";

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

    /**
     * 사용자 갱신 guard를 잡는다. 실패는 <b>그 사용자의 다른 갱신이 진행 중</b>이라는 뜻이고, 호출부는
     * 그 작업을 미반영 큐에 남긴다.
     *
     * @param taskId 진단용으로 guard에 남길 값
     */
    public boolean acquireGuard(long userId, String taskId, Duration ttl) {
        return redis.setIfAbsent(guardKey(userId), taskId, ttl);
    }

    /** 작업 종결 시 guard 반납. 실패해도 TTL이 정리하므로 호출부는 best-effort로 다룬다. */
    public void releaseGuard(long userId) {
        redis.delete(guardKey(userId));
    }

    static String guardKey(long userId) {
        return GUARD_KEY_PREFIX + userId;
    }
}
