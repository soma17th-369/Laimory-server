package com.laimory.server.push.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.push.entity.PushRegistration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * push_registrations 실 MySQL 왕복 검증.
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다(감사 컬럼 포함).
 * - native upsert의 원자 재결합(단일 owner), 멱등 재등록(freshness 갱신), 컬럼 binary collation
 *   (대소문자 다른 FID = 다른 설치), owner 조건 해제, invalid 일괄 삭제를 실제 unique key 위에서 확인한다.
 * - native 쓰기는 영속성 컨텍스트를 우회하므로 검증 전 clear로 1차 캐시를 비운다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest (스키마 변경 직후엔 볼륨 재생성 필요)
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@Transactional
class PushRegistrationPersistenceIntegrationTest {

    private static final long USER_A = 91_001L;
    private static final long USER_B = 91_002L;
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 7, 21, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 7, 21, 11, 30, 0);

    @Autowired
    private PushRegistrationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private List<PushRegistration> rowsInDb() {
        em.clear();
        return repository.findAll();
    }

    @Test
    void insertsNewFid_andFindsByUser() {
        repository.upsert(USER_A, "fid-a1", T1);
        repository.upsert(USER_A, "fid-a2", T1);
        repository.upsert(USER_B, "fid-b1", T1);

        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_A))
                .containsExactlyInAnyOrder("fid-a1", "fid-a2");
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_B))
                .containsExactly("fid-b1");
    }

    @Test
    void sameUserSameFid_reregistration_updatesFreshnessWithoutNewRow() {
        repository.upsert(USER_A, "fid-a1", T1);
        repository.upsert(USER_A, "fid-a1", T2);

        List<PushRegistration> rows = rowsInDb();
        assertThat(rows).hasSize(1);
        PushRegistration row = rows.get(0);
        assertThat(row.getUserId()).isEqualTo(USER_A);
        assertThat(row.getLastRegisteredAt()).isEqualTo(T2);
        // native upsert가 감사 컬럼을 직접 채운다 — created_at은 최초 등록, updated_at은 재등록 시각.
        assertThat(row.getCreatedAt()).isEqualTo(T1);
        assertThat(row.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    void differentUserSameFid_atomicallyRebindsSingleOwner() {
        // 계정 전환: unique FID 위 upsert가 원자적으로 owner를 덮는다 — 행은 늘지 않고 단일 owner 불변식 유지.
        repository.upsert(USER_A, "fid-shared", T1);
        repository.upsert(USER_B, "fid-shared", T2);

        List<PushRegistration> rows = rowsInDb();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(USER_B);
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_A)).isEmpty();
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_B)).containsExactly("fid-shared");
    }

    @Test
    void caseDifferingFids_areDistinctInstallations() {
        // 컬럼 단위 utf8mb4_bin: 대소문자만 다른 FID는 unique 충돌 없이 서로 다른 설치로 보존돼야 한다.
        repository.upsert(USER_A, "Fid-Case", T1);
        repository.upsert(USER_A, "fid-case", T1);

        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_A))
                .containsExactlyInAnyOrder("Fid-Case", "fid-case");
    }

    @Test
    void previousOwnerDelete_doesNotRemoveReboundRegistration() {
        // 계정 전환 뒤 이전 사용자(A)의 늦은 해제: (owner, FID) 동시 일치 조건이라 B의 등록은 남는다.
        repository.upsert(USER_A, "fid-shared", T1);
        repository.upsert(USER_B, "fid-shared", T2);

        int deleted = repository.deleteByUserIdAndFirebaseInstallationId(USER_A, "fid-shared");

        assertThat(deleted).isZero();
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_B)).containsExactly("fid-shared");
    }

    @Test
    void ownerDelete_isIdempotent() {
        repository.upsert(USER_A, "fid-a1", T1);

        assertThat(repository.deleteByUserIdAndFirebaseInstallationId(USER_A, "fid-a1")).isEqualTo(1);
        assertThat(repository.deleteByUserIdAndFirebaseInstallationId(USER_A, "fid-a1")).isZero();
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_A)).isEmpty();
    }

    @Test
    void invalidFidBatchDelete_removesOnlyGivenFids() {
        repository.upsert(USER_A, "fid-keep", T1);
        repository.upsert(USER_A, "fid-gone-1", T1);
        repository.upsert(USER_B, "fid-gone-2", T1);

        int deleted = repository.deleteInvalidRegistrations(List.of("fid-gone-1", "fid-gone-2"), T1);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_A)).containsExactly("fid-keep");
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_B)).isEmpty();
    }

    @Test
    void invalidFidBatchDelete_sparesRegistrationRefreshedAfterSnapshot() {
        // 발송 snapshot(T1) 이후 같은 FID가 재등록(T2)됐다면, 지연 도착한 무효 응답의 삭제가 최신 행을
        // 지우면 안 된다 — snapshot 조건부 삭제가 보호한다.
        repository.upsert(USER_A, "fid-revived", T1);
        repository.upsert(USER_A, "fid-revived", T2);

        int deleted = repository.deleteInvalidRegistrations(List.of("fid-revived"), T1);

        assertThat(deleted).isZero();
        assertThat(repository.findAllFirebaseInstallationIdsByUserId(USER_A)).containsExactly("fid-revived");
    }
}
