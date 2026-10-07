package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.laimory.server.config.CacheConfig.SingleFlightRedisCache;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * {@link SingleFlightRedisCache}의 서버 안 키별 single-flight 계약(#491, 사용자 승인된 동시성 전용 테스트).
 *
 * <p>Redis는 in-memory 대역으로 바꾼다 — 실제 non-locking writer처럼 "적재 경로도 저장소를 한 번 더 보고 없을 때만
 * 로더를 부른다"를 흉내 내므로, single-flight가 없으면 저장소가 비어 있는 동안 도착한 호출이 각자 로더를 부른다.
 * 대기 여부는 sleep이 아니라 스레드 상태 폴링으로 확인하고, 로더는 latch로 붙잡아 결정적으로 겹치게 한다.
 */
class SingleFlightRedisCacheTest {

    private static final String CACHE_NAME = "single-flight-test";
    private static final int CALLERS = 8;
    private static final String CALLER_THREAD_PREFIX = "single-flight-caller-";

    private final Map<String, byte[]> store = new ConcurrentHashMap<>();
    private final AtomicInteger loaderCalls = new AtomicInteger();
    private ExecutorService executor;
    private SingleFlightRedisCache cache;

    @BeforeEach
    void setUp() {
        AtomicInteger threadSeq = new AtomicInteger();
        executor = Executors.newFixedThreadPool(CALLERS + 1,
                runnable -> new Thread(runnable, CALLER_THREAD_PREFIX + threadSeq.incrementAndGet()));
        cache = new SingleFlightRedisCache(CACHE_NAME, inMemoryWriter(),
                RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(1)));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentMissesOnSameKeyRunLoaderOnceAndShareItsValue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Callable<String> loader = blockingLoader(release, "loaded");
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> cache.get("key", loader), executor);
        await().until(() -> loaderCalls.get() == 1);
        List<CompletableFuture<String>> followers = IntStream.range(1, CALLERS)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> cache.get("key", loader), executor))
                .toList();
        await().until(() -> waitingThreads() >= CALLERS);

        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("loaded");
        assertThat(CompletableFuture.allOf(followers.toArray(CompletableFuture[]::new))
                .thenApply(done -> followers.stream().map(CompletableFuture::join).toList())
                .get(5, TimeUnit.SECONDS)).containsOnly("loaded");
        assertThat(loaderCalls).hasValue(1);
    }

    @Test
    void loaderFailureIsSharedWithWaitersAndNextCallLoadsAgain() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Callable<String> failingLoader = () -> {
            loaderCalls.incrementAndGet();
            release.await();
            throw new IllegalStateException("db down");
        };
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> cache.get("key", failingLoader), executor);
        await().until(() -> loaderCalls.get() == 1);
        CompletableFuture<String> follower =
                CompletableFuture.supplyAsync(() -> cache.get("key", failingLoader), executor);
        await().until(() -> waitingThreads() >= 2);

        release.countDown();

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(Cache.ValueRetrievalException.class);
        assertThatThrownBy(() -> follower.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(Cache.ValueRetrievalException.class);
        assertThat(loaderCalls).hasValue(1);
        assertThat(cache.get("key", () -> {
            loaderCalls.incrementAndGet();
            return "recovered";
        })).isEqualTo("recovered");
        assertThat(loaderCalls).hasValue(2);
    }

    @Test
    void missOnAnotherKeyDoesNotWaitForInFlightLoad() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> blocked =
                CompletableFuture.supplyAsync(() -> cache.get("slow", blockingLoader(release, "slow")), executor);
        await().until(() -> loaderCalls.get() == 1);

        String other = CompletableFuture.supplyAsync(() -> cache.get("fast", () -> "fast"), executor)
                .get(5, TimeUnit.SECONDS);

        assertThat(other).isEqualTo("fast");
        assertThat(blocked).isNotDone();
        release.countDown();
        assertThat(blocked.get(5, TimeUnit.SECONDS)).isEqualTo("slow");
    }

    @Test
    void hitReturnsStoredValueWithoutLoader() {
        cache.put("key", "stored");

        String value = cache.get("key", () -> {
            loaderCalls.incrementAndGet();
            return "loaded";
        });

        assertThat(value).isEqualTo("stored");
        assertThat(loaderCalls).hasValue(0);
    }

    private Callable<String> blockingLoader(CountDownLatch release, String value) {
        return () -> {
            loaderCalls.incrementAndGet();
            release.await();
            return value;
        };
    }

    /** 로더 latch 또는 in-flight 적재 결과를 기다리며 멈춘 caller 스레드 수. */
    private long waitingThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(CALLER_THREAD_PREFIX))
                .filter(thread -> thread.getState() == Thread.State.WAITING
                        || thread.getState() == Thread.State.TIMED_WAITING)
                .filter(thread -> isCallerFrame(thread.getStackTrace()))
                .count();
    }

    private static boolean isCallerFrame(StackTraceElement[] frames) {
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().equals(SingleFlightRedisCache.class.getName())) {
                return true;
            }
        }
        return false;
    }

    /** non-locking writer의 "GET → 없으면 적재·SET" 흐름을 흉내 내는 in-memory 대역. */
    @SuppressWarnings("unchecked")
    private RedisCacheWriter inMemoryWriter() {
        RedisCacheWriter writer = mock(RedisCacheWriter.class);
        when(writer.get(eq(CACHE_NAME), any(byte[].class))).thenAnswer(call -> store.get(key(call.getArgument(1))));
        when(writer.get(eq(CACHE_NAME), any(byte[].class), any(Supplier.class), any(), anyBoolean()))
                .thenAnswer(call -> {
                    String key = key(call.getArgument(1));
                    byte[] stored = store.get(key);
                    if (stored != null) {
                        return stored;
                    }
                    byte[] loaded = ((Supplier<byte[]>) call.getArgument(2)).get();
                    store.put(key, loaded);
                    return loaded;
                });
        org.mockito.Mockito.doAnswer(call -> {
            store.put(key(call.getArgument(1)), call.getArgument(2));
            return null;
        }).when(writer).put(eq(CACHE_NAME), any(byte[].class), any(byte[].class), any());
        return writer;
    }

    private static String key(byte[] binaryKey) {
        return new String(binaryKey, StandardCharsets.UTF_8);
    }
}
