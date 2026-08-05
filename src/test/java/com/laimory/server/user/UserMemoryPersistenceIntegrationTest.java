package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * user_memories(JSON) ↔ MySQL 실 왕복 검증. 서버가 문서 내부를 해석하지 않으므로 검증 대상은 "받은
 * JSON이 구조·값 손실 없이 그대로 돌아오는가", 교체가 병합이 아닌 대체인가, 사용자 간 격리다.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다.
 * - 쓰기가 native upsert라 클래스 트랜잭션을 두지 않는다(직접 정리). 조회 전 clear로 1차 캐시를 비워
 *   DB JSON에서 실제로 재역직렬화하게 한다.
 * - users 로드는 이 테이블을 건드리지 않는다(분리 목적) — 사용자 행 없이 memory만 다뤄도 성립한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class UserMemoryPersistenceIntegrationTest {

    @Autowired
    private UserMemoryService userMemoryService;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(userId -> {
            userMemoryService.replace(userId, null);
            userRepository.deleteById(userId);
        });
        createdUserIds.clear();
    }

    private long newUserId() {
        Long userId = userRepository.save(
                User.of(Provider.GOOGLE, "sub-" + UUID.randomUUID(), "e@x.com", "nick")).getUserId();
        createdUserIds.add(userId);
        return userId;
    }

    private JsonNode reload(long userId) {
        em.clear();
        return userMemoryService.find(userId).orElse(null);
    }

    @Test
    void newUser_hasNoMemoryRow() {
        long userId = newUserId();

        assertThat(userMemoryService.find(userId)).isEmpty();
    }

    @Test
    void memory_roundTripsWithoutStructureOrValueLoss() throws Exception {
        // 서버가 해석하지 않는 임의 문서 — 중첩 객체·배열·유니코드·숫자/불리언/null 혼재.
        JsonNode document = objectMapper.readTree("""
                {"version":3,"active":true,"absent":null,
                 "profile":{"tone":"차분함","topics":["운동","독서"]},
                 "counters":{"saved":12,"ratio":0.75},
                 "history":[{"date":"2026-08-01","summary":"카페에서 작업 ☕"},
                            {"date":"2026-08-02","summary":"한강 러닝"}]}
                """);
        long userId = newUserId();

        userMemoryService.replace(userId, document);

        assertThat(reload(userId)).isEqualTo(document);
    }

    @Test
    void replace_swapsWholeDocumentInSingleRow() throws Exception {
        long userId = newUserId();
        userMemoryService.replace(userId, objectMapper.readTree("{\"keep\":1,\"drop\":2}"));

        JsonNode next = objectMapper.readTree("{\"keep\":9}");
        userMemoryService.replace(userId, next);

        // 병합이 아니라 교체 — 이전 문서의 key는 사라지고 행은 여전히 하나다(upsert).
        JsonNode loaded = reload(userId);
        assertThat(loaded).isEqualTo(next);
        assertThat(loaded.has("drop")).isFalse();
        assertThat(userMemoryService.find(userId)).isPresent();
    }

    @Test
    void replace_null_removesRow() throws Exception {
        long userId = newUserId();
        userMemoryService.replace(userId, objectMapper.readTree("{\"a\":1}"));

        userMemoryService.replace(userId, null);

        assertThat(reload(userId)).isNull();
    }

    @Test
    void replace_onAbsentRow_isIdempotent() {
        long userId = newUserId();

        userMemoryService.replace(userId, null); // 없는 메모리 제거는 0행 — 예외 없이 멱등이다.

        assertThat(userMemoryService.find(userId)).isEmpty();
    }

    @Test
    void memory_isIsolatedPerUser() throws Exception {
        long first = newUserId();
        long second = newUserId();

        userMemoryService.replace(first, objectMapper.readTree("{\"owner\":\"first\"}"));
        userMemoryService.replace(second, objectMapper.readTree("{\"owner\":\"second\"}"));
        userMemoryService.replace(first, objectMapper.readTree("{\"owner\":\"first-updated\"}"));

        assertThat(reload(first).get("owner").asText()).isEqualTo("first-updated");
        assertThat(reload(second).get("owner").asText()).isEqualTo("second");
    }

    /** 분리의 목적 — 로그인 경로의 User 조회는 memory 행과 무관하게 동작한다. */
    @Test
    void userLookup_isUnaffectedByMemory() throws Exception {
        long userId = newUserId();
        userMemoryService.replace(userId, objectMapper.readTree("{\"big\":\"document\"}"));

        em.clear();
        User user = userRepository.findById(userId).orElseThrow();

        assertThat(user.getUserId()).isEqualTo(userId);
        assertThat(userMemoryService.find(userId)).isPresent();
    }
}
