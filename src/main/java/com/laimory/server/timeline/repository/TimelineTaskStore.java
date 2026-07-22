package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
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
 * 환경 prefix(dev_ 등) 부착은 {@link RedisGateway}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class TimelineTaskStore {

    private static final String KEY_PREFIX = "timeline:draft-task:";
    private static final String DATE_GUARD_KEY_PREFIX = "timeline:date-guard:";

    private final RedisGateway redis;
    private final ObjectMapper objectMapper;

    public void save(String taskId, TimelineDraftTask task, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redis.set(KEY_PREFIX + taskId, json, ttl);
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
