package com.laimory.server.common.redis;

/**
 * Redis key 만료 알림의 수신자(#565). {@link RedisKeyExpirationSubscriber}가 환경 prefix를 넣은 keyspace 채널
 * 패턴으로 구독하고, 만료된 key를 prefix 없는 <b>논리 키</b>로 되돌려 전달한다.
 *
 * <p>알림은 pub/sub이라 fire-and-forget이다 — 구독이 끊긴 동안의 만료는 유실된다. 환경 안의 모든 서버가 같은
 * 알림을 받으므로 수신자가 중복 처리를 막아야 한다.
 */
public interface RedisKeyExpirationListener {

    /** 구독할 논리 키 prefix. 이 prefix로 시작하는 key의 만료만 전달된다. */
    String logicalKeyPrefix();

    /** 만료된 key의 논리 키. 호출은 구독 전용 executor 스레드에서 일어난다. */
    void onExpired(String logicalKey);
}
