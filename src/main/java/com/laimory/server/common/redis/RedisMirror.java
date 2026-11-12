package com.laimory.server.common.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

/**
 * Redis 이전용 dual write의 secondary(#638). {@link RedisGateway}가 primary 쓰기에 성공한 뒤 그 결과를 이 secondary에
 * 반영한다 — 원자 연산(SET NX·GETDEL·SET XX 등)의 판단은 primary가 하고, 여기에는 판단 결과만 보낸다.
 *
 * <p>접속 정보는 {@code app.redis.mirror.*}(env {@code REDIS_MIRROR_*})이며 host가 비면 비활성이다(모든 반영이 즉시
 * 반환). primary·secondary 전환은 코드가 아니라 배포 환경 {@code .env}에서 {@code REDIS_*}와 {@code REDIS_MIRROR_*}의
 * 값을 맞바꿔 재배포하는 것으로 한다 — Spring Cache·Spring Session·keyspace 만료 구독·readiness는 Boot 자동 구성 연결
 * 하나(primary)만 쓰므로 그때 함께 새 primary를 따른다. 이 클래스는 gateway의 key 쓰기만 반영한다.
 *
 * <p>연결은 bean으로 노출하지 않고 내부에서 만들고 닫는다. {@code RedisConnectionFactory}·{@code StringRedisTemplate}
 * bean을 하나 더 등록하면 Boot의 Redis 자동 구성({@code @ConditionalOnMissingBean})이 primary를 만들지 않는다.
 * 클라이언트 옵션은 primary({@code RedisClientConfig})와 같게 끊긴 동안 즉시 거절하고, timeout도
 * {@code spring.data.redis.*} 값을 그대로 쓴다. 연결은 첫 반영 때 맺으므로 secondary 장애가 기동·readiness를 막지 않는다.
 *
 * <p><b>secondary 실패는 요청을 실패시키지 않는다.</b> 예외를 삼키고 WARN만 남긴다. 지표·경보는 두지 않고 기준 전환
 * 전에 로그로 실패 0건을 확인한다(전환 절차는 deployment.md).
 *
 * <p>Spring Data Redis 타입을 직접 쓰는 {@code RedisAccessArchTest}의 승인 예외다 — 키는 gateway가 환경 prefix를 붙여 넘긴다.
 */
@Slf4j
@Component
public class RedisMirror implements DisposableBean {

    private final StringRedisTemplate template;
    private final LettuceConnectionFactory ownedConnectionFactory;

    @Autowired
    public RedisMirror(@Value("${app.redis.mirror.host:}") String host,
                       @Value("${app.redis.mirror.port:6379}") int port,
                       @Value("${app.redis.mirror.username:default}") String username,
                       @Value("${app.redis.mirror.password:}") String password,
                       @Value("${app.redis.mirror.ssl:true}") boolean ssl,
                       @Value("${spring.data.redis.timeout:2s}") Duration commandTimeout,
                       @Value("${spring.data.redis.connect-timeout:2s}") Duration connectTimeout) {
        if (host == null || host.isBlank()) {
            this.ownedConnectionFactory = null;
            this.template = null;
            return;
        }
        this.ownedConnectionFactory =
                connectionFactory(host, port, username, password, ssl, commandTimeout, connectTimeout);
        this.template = new StringRedisTemplate(ownedConnectionFactory);
        log.info("redis mirror enabled (dual write secondary)");
    }

    /** 주어진 연결로 반영한다. 연결 수명은 호출자 소유이며, {@code null}이면 비활성이다. */
    RedisMirror(StringRedisTemplate template) {
        this.template = template;
        this.ownedConnectionFactory = null;
    }

    private static LettuceConnectionFactory connectionFactory(String host, int port, String username, String password,
                                                              boolean ssl, Duration commandTimeout,
                                                              Duration connectTimeout) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        if (username != null && !username.isBlank()) {
            server.setUsername(username);
        }
        if (password != null && !password.isEmpty()) {
            server.setPassword(RedisPassword.of(password));
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                        .timeoutOptions(TimeoutOptions.enabled())
                        .build());
        if (ssl) {
            client.useSsl();
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client.build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    void set(String key, String value, Duration ttl) {
        mirror("set", key, t -> t.opsForValue().set(key, value, ttl));
    }

    /** {@code SET XX} — PX 또는 KEEPTTL. secondary에 키가 없으면(dual write 전 생성) 아무것도 쓰지 않는다. */
    void setIfPresent(String key, String value, Expiration expiration) {
        mirror("setIfPresent", key, t -> {
            byte[] rawKey = t.getStringSerializer().serialize(key);
            byte[] rawValue = t.getStringSerializer().serialize(value);
            t.execute((RedisCallback<Boolean>) connection ->
                    connection.stringCommands().set(rawKey, rawValue, expiration, SetOption.ifPresent()));
        });
    }

    void delete(String key) {
        mirror("delete", key, t -> t.delete(key));
    }

    void expire(String key, Duration ttl) {
        mirror("expire", key, t -> t.expire(key, ttl));
    }

    void addToSortedSet(String key, String member, long score) {
        mirror("zadd", key, t -> t.opsForZSet().add(key, member, score));
    }

    void addToSortedSetIfAbsent(String key, String member, long score) {
        mirror("zaddNx", key, t -> t.opsForZSet().addIfAbsent(key, member, score));
    }

    void removeFromSortedSet(String key, List<String> members) {
        mirror("zrem", key, t -> t.opsForZSet().remove(key, members.toArray(new Object[0])));
    }

    void pruneSortedSetByScore(String key, long maxScore) {
        mirror("zremrangebyscore", key,
                t -> t.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, maxScore));
    }

    private void mirror(String operation, String key, Consumer<StringRedisTemplate> command) {
        if (template == null) {
            return;
        }
        try {
            command.accept(template);
        } catch (RuntimeException e) {
            log.warn("redis mirror write failed: op={} key={} error={}", operation, key, e.toString());
        }
    }

    @Override
    public void destroy() {
        if (ownedConnectionFactory != null) {
            ownedConnectionFactory.destroy();
        }
    }
}
