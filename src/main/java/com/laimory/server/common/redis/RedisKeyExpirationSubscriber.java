package com.laimory.server.common.redis;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Redis keyspace {@code expired} 알림 구독(#565). {@link RedisKeyExpirationListener}의 논리 키 prefix에 환경
 * prefix를 붙인 채널 패턴 {@code __keyspace@<db>__:<env prefix><logical prefix>*}을 PSUBSCRIBE한다 — 환경 prefix가
 * 패턴에 들어가므로 dev·test·prod가 같은 Redis를 공유해도 Redis가 환경별로 나눠 보낸다.
 *
 * <p>Redis 설정 {@code notify-keyspace-events}에 {@code K}(keyspace)와 {@code x}(expired)가 있어야 알림이 온다.
 * 설정은 운영 단계가 소유하며 앱은 바꾸지 않는다(CONFIG SET 없음). 기동 뒤 {@code CONFIG GET}으로 확인해 누락·조회
 * 실패를 ERROR로 남기고 기동은 계속한다 — 기존 ERROR 경보가 감지한다.
 *
 * <p>구독은 기동 완료 뒤에 건다. 컨테이너가 시작 시점에 구독 등록을 기다리다 실패하면 앱 기동이 실패하는데,
 * Redis 장애가 앱 기동을 막지 않던 기존 동작을 바꾸지 않기 위해서다. 등록 실패는 ERROR로 남기고 기동은 계속한다.
 * 첫 구독 시도가 Redis 연결을 얻지 못하면 Spring Data Redis는 재시도하지 않는다 — 그 서버는 재시작 전까지 만료
 * 알림을 받지 못한다(수용 — 같은 환경의 다른 서버는 영향 없고 ERROR 경보가 감지한다). 구독이 한 번 성립한 뒤의
 * 연결 끊김은 컨테이너가 backoff로 다시 구독한다(끊긴 동안의 알림은 유실).
 *
 * <p>Spring Data Redis 타입을 직접 쓰는 {@code RedisAccessArchTest}의 승인 예외다 — pub/sub 구독은
 * {@link RedisGateway}의 key 연산 경계 밖이고, 환경 prefix는 같은 {@code app.redis.key-prefix}로 붙인다.
 */
@Slf4j
@Component
public class RedisKeyExpirationSubscriber implements DisposableBean {

    private static final String NOTIFY_KEYSPACE_EVENTS = "notify-keyspace-events";
    private static final String EXPIRED_EVENT = "expired";

    private final RedisConnectionFactory connectionFactory;
    private final RedisKeyExpirationListener listener;
    private final String channelPrefix;
    private final ThreadPoolTaskExecutor executor;
    private final RedisMessageListenerContainer container;

    public RedisKeyExpirationSubscriber(RedisConnectionFactory connectionFactory,
                                        RedisKeyExpirationListener listener,
                                        @Value("${app.redis.key-prefix:}") String keyPrefix,
                                        @Value("${spring.data.redis.database:0}") int database) {
        this.connectionFactory = connectionFactory;
        this.listener = listener;
        this.channelPrefix = "__keyspace@" + database + "__:" + keyPrefix;
        // 만료 처리는 유실된 작업에서만 일어나는 드문 DB 작업이라 작은 고정 풀로 둔다.
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(2);
        this.executor.setMaxPoolSize(2);
        this.executor.setThreadNamePrefix("redis-key-expiration-");
        this.executor.initialize();
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
        this.container.setTaskExecutor(executor);
        this.container.afterPropertiesSet();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        logErrorIfExpiredNotificationsDisabled();
        String pattern = channelPrefix + listener.logicalKeyPrefix() + "*";
        try {
            container.start();
            container.addMessageListener(this::onMessage, new PatternTopic(pattern));
            log.info("redis key expiration subscribed: pattern={}", pattern);
        } catch (RuntimeException e) {
            log.error("redis key expiration subscribe failed (재시작 전까지 이 서버는 만료 처리 안 함): pattern={}",
                    pattern, e);
        }
    }

    private void onMessage(Message message, byte[] pattern) {
        // 기존 설정에 다른 이벤트 플래그가 있으면 같은 채널로 set·del 등도 온다 — expired만 처리한다.
        if (!EXPIRED_EVENT.equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
            return;
        }
        logicalKeyOf(new String(message.getChannel(), StandardCharsets.UTF_8), channelPrefix)
                .ifPresent(logicalKey -> {
                    try {
                        listener.onExpired(logicalKey);
                    } catch (RuntimeException e) {
                        log.error("redis key expiration handling failed: key={}", logicalKey, e);
                    }
                });
    }

    /** keyspace 채널 이름에서 채널·환경 prefix를 걷어 논리 키로 되돌린다. 다른 prefix의 채널은 비어 있다. */
    static Optional<String> logicalKeyOf(String channel, String channelPrefix) {
        return channel.startsWith(channelPrefix)
                ? Optional.of(channel.substring(channelPrefix.length()))
                : Optional.empty();
    }

    /** {@code K}(keyspace)와 {@code x}(expired — {@code A}는 x를 포함) 플래그가 모두 있어야 구독이 알림을 받는다. */
    static boolean enablesExpiredKeyspaceEvents(String flags) {
        return flags != null && flags.contains("K") && (flags.contains("x") || flags.contains("A"));
    }

    private void logErrorIfExpiredNotificationsDisabled() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Properties config = connection.serverCommands().getConfig(NOTIFY_KEYSPACE_EVENTS);
            String flags = config == null ? null : config.getProperty(NOTIFY_KEYSPACE_EVENTS);
            if (!enablesExpiredKeyspaceEvents(flags)) {
                log.error("redis notify-keyspace-events lacks K and x — key expiration events will not arrive: "
                        + "flags={}", flags);
            }
        } catch (RuntimeException e) {
            log.error("redis notify-keyspace-events check failed — key expiration events may not arrive", e);
        }
    }

    @Override
    public void destroy() throws Exception {
        container.destroy();
        executor.shutdown();
    }
}
