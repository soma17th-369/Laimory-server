package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.PrefixedRedis;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * timeline draft 작업 상태의 Redis 데이터 접근 계층.
 * 논리 키: {@code timeline:draft-task:{taskId}}, 값: TimelineDraftTask JSON.
 * 날짜 guard 논리 키: {@code timeline:date-guard:{userId}:{recordDate}}, 값: holder 문자열.
 * 환경 prefix(dev_ 등) 부착은 {@link PrefixedRedis} facade가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class TimelineTaskStore {

    private static final String KEY_PREFIX = "timeline:draft-task:";
    private static final String TOKEN_USES_KEY_PREFIX = "timeline:callback-token-uses:";
    private static final String DATE_GUARD_KEY_PREFIX = "timeline:date-guard:";

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

    /** 날짜 guard를 holder 명의로 선점한다(SET NX). 선점했으면 true, 이미 다른 작업이 잡고 있으면 false. */
    public boolean claimDateGuard(long userId, LocalDate recordDate, String holder, Duration ttl) {
        return redis.setIfAbsent(dateGuardKey(userId, recordDate), holder, ttl);
    }

    /** 현재 값이 내 holder일 때만 guard TTL을 갱신한다(원자). 갱신했으면 true. */
    public boolean refreshDateGuard(long userId, LocalDate recordDate, String holder, Duration ttl) {
        return redis.expireIfValueMatches(dateGuardKey(userId, recordDate), holder, ttl);
    }

    /** 현재 값이 내 holder일 때만 guard를 해제한다(compare-and-release). 해제했으면 true. */
    public boolean releaseDateGuard(long userId, LocalDate recordDate, String holder) {
        return redis.deleteIfValueMatches(dateGuardKey(userId, recordDate), holder);
    }

    /** recordDate는 ISO({@code yyyy-MM-dd}) 문자열로 붙인다. */
    private static String dateGuardKey(long userId, LocalDate recordDate) {
        return DATE_GUARD_KEY_PREFIX + userId + ":" + recordDate;
    }
}
