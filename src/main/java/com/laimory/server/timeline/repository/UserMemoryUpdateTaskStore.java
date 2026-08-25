package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * User Memory 갱신 <b>진행 상태</b>의 Redis 데이터 접근 계층 — 작업 하나와 사용자 guard를 함께 다룬다.
 * 논리 키: 작업이 {@code timeline:user-memory-update:{taskId}}(값 UserMemoryUpdateTask JSON),
 * guard가 {@code timeline:user-memory-update:user:{canonicalUuid(subjectId)}}(SET NX, TTL).
 * 환경 prefix 부착은 {@link RedisGateway}가 담당한다.
 *
 * <p><b>key 존재 자체가 진행 중</b>이라 status 필드도, terminal 값을 남기는 저장도 없다 — 종결은 삭제다.
 * 그래서 뒤늦게 도착한 결과나 중복 결과는 자연히 404로 떨어지고, AI는 4xx를 재시도 중단 신호로 읽는다.
 * 단계가 하나뿐이라 index도 조건부 write도 두지 않는다(draft task와의 차이 — 그쪽은 processing
 * index와 native {@code SET XX} write를 쓴다).
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
    public boolean acquireGuard(UUID subjectId, String taskId, Duration ttl) {
        return redis.setIfAbsent(guardKey(subjectId), taskId, ttl);
    }

    /**
     * guard를 즉시 반납한다. <b>갱신 흐름은 이것을 호출하지 않는다</b> — 반납은 TTL에 맡긴다.
     *
     * <p>일찍 반납하는 이유는 "그 사용자의 <b>다음</b> 갱신이 TTL 동안 막히지 않게"였는데, 접수가 하루
     * 1회 배치로 일원화되면서 다음 접수가 24시간 뒤가 됐다. TTL 3분이 남아 있든 말든 막는 것이 없다.
     *
     * <p>안 부르는 편이 더 안전하기도 하다. 이 삭제는 값(taskId)을 대조하지 않아 <b>남의 guard를 지울
     * 수 있다</b> — guard가 task보다 먼저 만료되므로(guard 획득이 task 저장보다 앞선다) 그 틈에 다른
     * 인스턴스가 새로 잡은 guard를 뒤늦은 결과가 지우는 창이 있다. 호출하지 않으면 그 창 자체가 없다.
     *
     * <p><b>cron 주기를 guard TTL 가까이 줄이거나 즉시 접수를 되살린다면 이 판단이 뒤집힌다</b> —
     * 그때는 반납이 필요하고, 소유권을 대조하는 CAS(Lua)로 만들어야 한다.
     */
    public void releaseGuard(UUID subjectId) {
        redis.delete(guardKey(subjectId));
    }

    static String guardKey(UUID subjectId) {
        return GUARD_KEY_PREFIX + java.util.Objects.requireNonNull(subjectId, "subjectId");
    }
}
