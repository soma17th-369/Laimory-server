package com.laimory.server.auth.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.PrefixedRedis;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * app_code의 Redis 데이터 접근 계층. 논리 키: {@code auth:app-code:{sha256hex(원문)}},
 * 값: {@link AppCodeEntry} JSON. 원문 code는 저장하지 않는다(해시가 곧 키).
 * 소비는 GETDEL로 원자 보장 — 같은 코드의 동시 교환 시 정확히 한 요청만 값을 받는다.
 */
@Component
@RequiredArgsConstructor
public class AppCodeStore {

    private static final String KEY_PREFIX = "auth:app-code:";

    private final PrefixedRedis redis;
    private final ObjectMapper objectMapper;

    /** app_code 소유자(userId)와 핸드오프 PKCE challenge. */
    public record AppCodeEntry(long userId, String appChallenge) {
    }

    public void save(String appCodeHash, AppCodeEntry entry, Duration ttl) {
        try {
            redis.set(KEY_PREFIX + appCodeHash, objectMapper.writeValueAsString(entry), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AppCodeEntry 직렬화에 실패했습니다.", e);
        }
    }

    /** 원자적으로 읽고 삭제한다(일회 소비). 없으면(미발급·만료·이미 소비) null. */
    public AppCodeEntry consume(String appCodeHash) {
        String json = redis.getAndDelete(KEY_PREFIX + appCodeHash);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AppCodeEntry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AppCodeEntry 역직렬화에 실패했습니다.", e);
        }
    }
}
