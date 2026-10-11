package com.laimory.server.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * keyspace 만료 알림 구독의 순수 판정(#565) — 채널 이름을 논리 키로 되돌리는 것과 기동 시 설정 검사 기준.
 * 실제 구독·알림 배선은 {@code TimelineTaskLossIntegrationTest}가 실 Redis로 검증한다.
 */
class RedisKeyExpirationSubscriberTest {

    private static final String DEV_CHANNEL_PREFIX = "__keyspace@0__:dev_";

    @Test
    void channelOfOwnEnvironmentIsTurnedIntoLogicalKey() {
        assertThat(RedisKeyExpirationSubscriber.logicalKeyOf(
                "__keyspace@0__:dev_timeline:draft-task:loss-timer:abc", DEV_CHANNEL_PREFIX))
                .contains("timeline:draft-task:loss-timer:abc");
    }

    @Test
    void channelOfOtherEnvironmentYieldsNothing() {
        // prod(빈 prefix) 키의 채널이 dev 구독자에게 와도 처리하지 않는다.
        assertThat(RedisKeyExpirationSubscriber.logicalKeyOf(
                "__keyspace@0__:timeline:draft-task:loss-timer:abc", DEV_CHANNEL_PREFIX)).isEmpty();
    }

    @Test
    void keyspaceAndExpiredFlagsEnableExpirationEvents() {
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents("Kx")).isTrue();
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents("xKE")).isTrue();
        // A는 x를 포함하는 별칭이다.
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents("KA")).isTrue();
    }

    @Test
    void missingKeyspaceOrExpiredFlagDisablesExpirationEvents() {
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents("")).isFalse();
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents(null)).isFalse();
        // keyevent 채널(E)만 켜져 있으면 keyspace 채널 구독은 알림을 받지 못한다.
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents("Ex")).isFalse();
        assertThat(RedisKeyExpirationSubscriber.enablesExpiredKeyspaceEvents("Kg")).isFalse();
    }
}
