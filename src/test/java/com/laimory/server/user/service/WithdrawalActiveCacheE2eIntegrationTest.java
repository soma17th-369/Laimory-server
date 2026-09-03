package com.laimory.server.user.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.AccountErasureJobRepository;
import com.laimory.server.user.repository.UserRepository;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 탈퇴 ↔ ACTIVE 캐시 E2E(#429) — 실 MySQL·Redis 위에서 "필터 warm-up → 탈퇴 transaction commit →
 * evict → 같은 토큰 차단" 전체 배선(security chain의 실제 필터·오케스트레이터·커밋 순서)을 고정한다.
 *
 * <p>첫 DELETE 요청이 필터 miss로 캐시를 적재하고, 탈퇴 처리 뒤 evict가 그 엔트리를 지운다.
 * evict 배선이 빠지거나 끊기면 두 번째 DELETE가 stale hit로 인증을 통과해 멱등 202로 수렴하므로
 * 이 테스트(401 기대)가 실패한다 — 단위 테스트가 못 보는 오케스트레이션 회귀를 여기서 잡는다.
 *
 * 실행: docker compose up -d --wait 후 ./gradlew integrationTest
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("docker")
@Tag("integration")
class WithdrawalActiveCacheE2eIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private SubjectMappingService subjectMappingService;
    @Autowired
    private JwtTokens jwtTokens;
    @Autowired
    private RedisGateway redisGateway;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    @Autowired
    private AccountErasureJobRepository accountErasureJobRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdUserId;
    private UUID createdSubjectId;

    @AfterEach
    void cleanUp() {
        if (createdUserId == null) {
            return;
        }
        // FK 순서는 UserWithdrawalIntegrationTest와 동일: job(users FK) → 설정 행 → user → mapping.
        accountErasureJobRepository.findAll().stream()
                .filter(job -> job.getUserId().equals(createdUserId))
                .forEach(accountErasureJobRepository::delete);
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, createdSubjectId);
        userRepository.deleteById(createdUserId);
        userSubjectLinkRepository.deleteById(subjectLookupKeyDeriver.deriveCurrent(createdUserId));
        redisGateway.delete(RedisActiveStatusCache.KEY_PREFIX + createdUserId);
    }

    @Test
    void sameTokenIsRejectedImmediatelyAfterWithdrawalCommit() throws Exception {
        User user = newUserProvisioner.provision(Provider.KAKAO,
                "auth-cache-e2e-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L),
                null, null);
        createdUserId = user.getUserId();
        createdSubjectId = subjectMappingService.getRequired(createdUserId);
        String bearer = "Bearer " + jwtTokens.issueAccessToken(createdUserId);

        // 첫 요청: 필터가 miss → DB(ACTIVE) → 캐시 적재를 거쳐 인증되고, 탈퇴가 commit 후 evict한다.
        mockMvc.perform(delete("/a/api/v1/user").header("Authorization", bearer))
                .andExpect(status().isAccepted());

        // 같은 토큰의 다음 요청: evict 덕에 miss → DB(WITHDRAWAL_PENDING) → 401 -2001.
        // evict가 빠지면 warm 엔트리가 살아 있어 202(멱등 탈퇴)로 수렴하고 이 검증이 실패한다.
        mockMvc.perform(delete("/a/api/v1/user").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));
    }
}
