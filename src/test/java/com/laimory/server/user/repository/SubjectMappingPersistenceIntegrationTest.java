package com.laimory.server.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.entity.UserSubjectLink;
import com.laimory.server.user.service.NewUserProvisioner;
import com.laimory.server.user.service.SubjectMappingService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * user_subject_links ↔ MySQL 실 왕복 검증(#282).
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 BINARY(32)/VARCHAR(36)/SMALLINT ↔ 엔티티 매핑 정합을
 *   검증한다(이 저장소 최초의 BINARY 컬럼 매핑).
 * - provisioner의 원자성(신규 user와 mapping이 한 transaction — mapping 실패 시 user까지 rollback)은
 *   실 DB에서만 성립하므로 여기서 검증한다. 실패 주입은 spy stubbing으로 한다.
 * - repository·deriver 직접 접근은 테스트 한정 예외다(arch rule은 main 코드만 검사).
 *
 * 실행: docker compose down -v && docker compose up -d --wait 후 ./gradlew integrationTest
 * (schema.sql은 빈 데이터 볼륨 첫 기동에만 적용 — user_subject_links DDL 반영에 볼륨 재생성 필요)
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class SubjectMappingPersistenceIntegrationTest {

    @Autowired
    private NewUserProvisioner newUserProvisioner;

    // 기본은 실 빈으로 위임(pass-through), 실패 주입 테스트만 doThrow — 테스트마다 자동 reset된다.
    @MockitoSpyBean
    private SubjectMappingService subjectMappingService;

    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;

    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<byte[]> createdLookupKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 가입 transaction이 subject 축 기본 설정 행도 만든다(#314) — FK RESTRICT라 mapping보다 먼저 지운다.
        createdLookupKeys.forEach(key -> userSubjectLinkRepository.findById(key).ifPresent(link ->
                SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, link.getSubjectId())));
        createdLookupKeys.forEach(userSubjectLinkRepository::deleteById);
        createdLookupKeys.clear();
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    private static byte[] lookupKey(int seed) {
        byte[] key = new byte[32];
        key[0] = (byte) seed;
        key[31] = (byte) (seed + 1);
        return key;
    }

    @Test
    void provision_createsExactlyOneUserAndOneMapping_bytesRoundTripByPrimaryKey() {
        String providerUserId = "sub-" + UUID.randomUUID();

        User user = newUserProvisioner.provision(Provider.GOOGLE, providerUserId, "e@x.com", "nick");
        long userId = user.getUserId();
        byte[] currentLookupKey = subjectLookupKeyDeriver.deriveCurrent(userId);
        createdUserIds.add(userId);
        createdLookupKeys.add(currentLookupKey);

        em.clear(); // 1차 캐시를 비워 DB 문자열에서 실제로 재조회하게 한다
        UserSubjectLink link = userSubjectLinkRepository.findById(currentLookupKey).orElseThrow();
        assertThat(link.getUserLookupKey()).isEqualTo(currentLookupKey);     // BINARY(32) PK 왕복
        assertThat(link.getSubjectId().version()).isEqualTo(4);              // UUIDv4
        assertThat(link.getSubjectId().variant()).isEqualTo(2);              // RFC 4122 variant
        assertThat(link.getLookupKeyVersion()).isEqualTo(subjectLookupKeyDeriver.currentVersion());
        assertThat(userRepository.findById(userId)).isPresent();             // user·mapping 정확히 1:1
        // 일반 경로(getRequired)도 같은 subject로 해석된다.
        assertThat(subjectMappingService.getRequired(userId)).isEqualTo(link.getSubjectId());
    }

    @Test
    void provision_mappingFailure_rollsBackUserToo() {
        String providerUserId = "sub-" + UUID.randomUUID();
        long linkCountBefore = userSubjectLinkRepository.count();
        // spy는 @Transactional 프록시 안쪽에 있다 — 주입 필드에 직접 stubbing하면 호출이 프록시의
        // MANDATORY 검사부터 타므로(트랜잭션 없음 → 예외) 프록시를 벗겨 spy 본체에 stubbing한다.
        SubjectMappingService spy = AopTestUtils.getUltimateTargetObject(subjectMappingService);
        doThrow(new IllegalStateException("주입된 mapping 실패")).when(spy).createFor(anyLong());

        assertThatThrownBy(() ->
                newUserProvisioner.provision(Provider.GOOGLE, providerUserId, "e@x.com", "nick"))
                .isInstanceOf(IllegalStateException.class);

        // 부분 user·orphan subject 금지 — provisioner transaction 전체가 rollback됐어야 한다.
        assertThat(userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, providerUserId))
                .isEmpty();
        assertThat(userSubjectLinkRepository.count()).isEqualTo(linkCountBefore);
    }

    @Test
    void concurrentProvisionSameProvider_leavesOneUserAndOneMappingWithoutLoserOrphan() throws Exception {
        String providerUserId = "sub-" + UUID.randomUUID();
        long userCountBefore = userRepository.count();
        long linkCountBefore = userSubjectLinkRepository.count();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Object> provision = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                return newUserProvisioner.provision(
                        Provider.GOOGLE, providerUserId, "e@x.com", "nick");
            } catch (DataIntegrityViolationException loser) {
                return loser;
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Object> outcomes;
        try {
            Future<Object> first = executor.submit(provision);
            Future<Object> second = executor.submit(provision);
            outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(outcomes).filteredOn(User.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(DataIntegrityViolationException.class::isInstance).hasSize(1);
        User winner = userRepository.findByProviderAndProviderUserId(Provider.GOOGLE, providerUserId)
                .orElseThrow();
        byte[] winnerLookupKey = subjectLookupKeyDeriver.deriveCurrent(winner.getUserId());
        createdUserIds.add(winner.getUserId());
        createdLookupKeys.add(winnerLookupKey);

        assertThat(userRepository.count()).isEqualTo(userCountBefore + 1);
        assertThat(userSubjectLinkRepository.count()).isEqualTo(linkCountBefore + 1);
        assertThat(userSubjectLinkRepository.findById(winnerLookupKey)).isPresent();
        assertThat(subjectMappingService.getRequired(winner.getUserId())).isNotNull();
    }

    @Test
    void getRequired_withoutMapping_failsClosedWithoutCreating() {
        // legacy 경로(직접 insert)로 mapping 없는 사용자를 만든다 — 일반 경로는 자동 생성하지 않는다.
        User user = userRepository.save(
                User.of(Provider.GOOGLE, "sub-" + UUID.randomUUID(), "e@x.com", "nick"));
        long userId = user.getUserId();
        createdUserIds.add(userId);

        assertThatThrownBy(() -> subjectMappingService.getRequired(userId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(userSubjectLinkRepository.findById(subjectLookupKeyDeriver.deriveCurrent(userId)))
                .isEmpty();
    }

    @Test
    @Transactional // 테스트 트랜잭션 rollback으로 자동 정리
    void subjectId_uniqueConstraint_rejectsDuplicateSubject() {
        UUID subject = UUID.randomUUID();
        userSubjectLinkRepository.saveAndFlush(UserSubjectLink.of(lookupKey(10), subject, (short) 1));

        assertThatThrownBy(() -> userSubjectLinkRepository.saveAndFlush(
                UserSubjectLink.of(lookupKey(20), subject, (short) 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional // 테스트 트랜잭션 rollback으로 자동 정리
    void rekey_swapsPrimaryKeyAndVersionAtomically_preservingSubject() {
        UUID subject = UUID.randomUUID();
        userSubjectLinkRepository.saveAndFlush(UserSubjectLink.of(lookupKey(30), subject, (short) 1));

        int affected = userSubjectLinkRepository.rekey(lookupKey(30), lookupKey(40), (short) 2);

        assertThat(affected).isEqualTo(1);
        em.clear(); // native 벌크 UPDATE는 1차 캐시를 우회한다 — DB 상태로 재조회
        assertThat(userSubjectLinkRepository.findById(lookupKey(30))).isEmpty();
        UserSubjectLink rekeyed = userSubjectLinkRepository.findById(lookupKey(40)).orElseThrow();
        assertThat(rekeyed.getSubjectId()).isEqualTo(subject);               // subject 불변
        assertThat(rekeyed.getLookupKeyVersion()).isEqualTo((short) 2);

        // 이미 교체된 구 PK 재시도는 0행 — 동시 교체 경합의 멱등 계약.
        assertThat(userSubjectLinkRepository.rekey(lookupKey(30), lookupKey(40), (short) 2)).isZero();
    }
}
