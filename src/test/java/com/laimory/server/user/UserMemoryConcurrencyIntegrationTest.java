package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User Memory 교체 ↔ 로그인 nickname 갱신의 교차-필드 lost update 회귀 검증(실 MySQL).
 *
 * <p>{@code User}의 {@code @DynamicUpdate}가 지키는 불변식: 두 트랜잭션이 같은 row를 읽고 서로 다른
 * 필드 그룹을 갱신해 순차 커밋해도, 나중 커밋이 상대의 변경을 자신의 로드 시점 스냅샷으로 되돌리지
 * 않는다. {@code @DynamicUpdate}가 없으면 Hibernate 기본 UPDATE가 모든 updatable 컬럼을 SET에 포함해
 * 이 테스트는 실패해야 한다(재로그인 UPDATE가 방금 저장한 user_memory를 로드 시점 null로 덮어씀).
 *
 * <p>{@code TimelineEventEditConcurrencyIntegrationTest}와 같은 구조다 — 한 트랜잭션·한 영속성
 * 컨텍스트 안에서는 관리 인스턴스가 하나뿐이라 이 경합이 재현되지 않으므로 스레드를 나눈다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class UserMemoryConcurrencyIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(
                        User.of(Provider.KAKAO, "sub-" + UUID.randomUUID(), null, "원래 닉네임"))
                .getUserId();
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteById(userId);
    }

    @Test
    void concurrentUserMemoryAndNicknameEdits_bothChangesSurvive() throws Exception {
        // 타임라인: 두 트랜잭션이 같은 user를 각자 로드(스냅샷 확보) → A가 user_memory 커밋 →
        // B가 nickname 커밋. 최종 상태에 A의 memory와 B의 nickname이 모두 남아야 한다.
        JsonNode memory = objectMapper.readTree("{\"version\":1,\"summary\":\"누적 요약\"}");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothLoaded = new CountDownLatch(2);
        CountDownLatch memoryCommitted = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> memoryWriter = pool.submit(() -> { // User Memory 교체(후속 SAVED 전이 흐름)
                tx.executeWithoutResult(status -> {
                    User user = userRepository.findById(userId).orElseThrow();
                    bothLoaded.countDown();
                    await(bothLoaded); // B도 로드를 마친 뒤에만 커밋으로 진행(스냅샷 교차 보장)
                    user.replaceUserMemory(memory);
                }); // executeWithoutResult 반환 = 커밋 완료
                memoryCommitted.countDown();
            });
            Future<?> nicknameWriter = pool.submit(() -> { // Kakao 재로그인의 nickname 갱신 — 마지막 커밋
                tx.executeWithoutResult(status -> {
                    User user = userRepository.findById(userId).orElseThrow();
                    bothLoaded.countDown();
                    await(memoryCommitted); // A의 커밋 이후에 자신의 변경을 커밋한다
                    user.updateNickname("새 닉네임");
                });
            });
            memoryWriter.get(30, TimeUnit.SECONDS);
            nicknameWriter.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        User after = userRepository.findById(userId).orElseThrow();
        assertThat(after.getUserMemory()).isEqualTo(memory); // @DynamicUpdate 부재 시 B의 UPDATE가 null로 되돌림
        assertThat(after.getNickname()).isEqualTo("새 닉네임");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch await timed out").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting latch", e);
        }
    }
}
