package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * users.user_memory(JSON) ↔ MySQL 실 왕복 검증. 서버는 문서 내부를 해석하지 않으므로 검증 대상은
 * "받은 JSON이 구조·값 손실 없이 그대로 돌아오는가"와 사용자 간 격리다.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다.
 * - flush+clear로 1차 캐시를 비워 DB JSON에서 실제로 재역직렬화하게 한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class UserMemoryPersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** provider_user_id는 (provider, provider_user_id) UNIQUE라 테스트마다 새 값을 쓴다. */
    private User newUser() {
        return userRepository.save(
                User.of(Provider.GOOGLE, "sub-" + UUID.randomUUID(), "e@x.com", "nick"));
    }

    private User reload(Long userId) {
        em.flush();
        em.clear();
        return userRepository.findById(userId).orElseThrow();
    }

    @Test
    void newUser_hasNullUserMemory() {
        Long userId = newUser().getUserId();

        assertThat(reload(userId).getUserMemory()).isNull();
    }

    @Test
    void userMemory_roundTripsWithoutStructureOrValueLoss() throws Exception {
        // 서버가 해석하지 않는 임의 문서 — 중첩 객체·배열·유니코드·숫자/불리언/null 혼재.
        JsonNode document = objectMapper.readTree("""
                {"version":3,"active":true,"absent":null,
                 "profile":{"tone":"차분함","topics":["운동","독서"]},
                 "counters":{"saved":12,"ratio":0.75},
                 "history":[{"date":"2026-08-01","summary":"카페에서 작업 ☕"},
                            {"date":"2026-08-02","summary":"한강 러닝"}]}
                """);
        User user = newUser();
        user.replaceUserMemory(document);
        userRepository.saveAndFlush(user);

        JsonNode loaded = reload(user.getUserId()).getUserMemory();

        assertThat(loaded).isEqualTo(document);
    }

    @Test
    void userMemory_replaceSwapsWholeDocument() throws Exception {
        User user = newUser();
        user.replaceUserMemory(objectMapper.readTree("{\"keep\":1,\"drop\":2}"));
        userRepository.saveAndFlush(user);

        JsonNode next = objectMapper.readTree("{\"keep\":9}");
        User reloaded = reload(user.getUserId());
        reloaded.replaceUserMemory(next);
        userRepository.saveAndFlush(reloaded);

        // 병합이 아니라 교체 — 이전 문서의 key는 사라진다.
        JsonNode loaded = reload(user.getUserId()).getUserMemory();
        assertThat(loaded).isEqualTo(next);
        assertThat(loaded.has("drop")).isFalse();
    }

    @Test
    void userMemory_replaceWithNull_clearsColumn() throws Exception {
        User user = newUser();
        user.replaceUserMemory(objectMapper.readTree("{\"a\":1}"));
        userRepository.saveAndFlush(user);

        User reloaded = reload(user.getUserId());
        reloaded.replaceUserMemory(null);
        userRepository.saveAndFlush(reloaded);

        assertThat(reload(user.getUserId()).getUserMemory()).isNull();
    }

    @Test
    void userMemory_isIsolatedPerUser() throws Exception {
        User first = newUser();
        User second = newUser();
        first.replaceUserMemory(objectMapper.readTree("{\"owner\":\"first\"}"));
        second.replaceUserMemory(objectMapper.readTree("{\"owner\":\"second\"}"));
        userRepository.saveAndFlush(first);
        userRepository.saveAndFlush(second);

        assertThat(reload(first.getUserId()).getUserMemory().get("owner").asText()).isEqualTo("first");
        assertThat(reload(second.getUserId()).getUserMemory().get("owner").asText()).isEqualTo("second");
    }

}
