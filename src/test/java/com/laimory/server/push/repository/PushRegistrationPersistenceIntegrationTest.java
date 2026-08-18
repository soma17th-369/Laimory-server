package com.laimory.server.push.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.push.entity.PushRegistration;
import com.laimory.server.testsupport.TestSubjects;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

    private static final UUID SUBJECT_A = TestSubjects.id(91_001L);
    private static final UUID SUBJECT_B = TestSubjects.id(91_002L);
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
        repository.upsert(SUBJECT_A.toString(), "fid-a1", null, T1);
        repository.upsert(SUBJECT_A.toString(), "fid-a2", null, T1);
        repository.upsert(SUBJECT_B.toString(), "fid-b1", null, T1);

        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_A))
                .containsExactlyInAnyOrder("fid-a1", "fid-a2");
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_B))
                .containsExactly("fid-b1");
    }

    @Test
    void sameUserSameFid_reregistration_updatesFreshnessWithoutNewRow() {
        repository.upsert(SUBJECT_A.toString(), "fid-a1", null, T1);
        repository.upsert(SUBJECT_A.toString(), "fid-a1", null, T2);

        List<PushRegistration> rows = rowsInDb();
        assertThat(rows).hasSize(1);
        PushRegistration row = rows.get(0);
        assertThat(row.getSubjectId()).isEqualTo(SUBJECT_A);
        assertThat(row.getLastRegisteredAt()).isEqualTo(T2);
        // native upsert가 감사 컬럼을 직접 채운다 — created_at은 최초 등록, updated_at은 재등록 시각.
        assertThat(row.getCreatedAt()).isEqualTo(T1);
        assertThat(row.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    void differentUserSameFid_atomicallyRebindsSingleOwner() {
        // 계정 전환: unique FID 위 upsert가 원자적으로 owner를 덮는다 — 행은 늘지 않고 단일 owner 불변식 유지.
        repository.upsert(SUBJECT_A.toString(), "fid-shared", null, T1);
        repository.upsert(SUBJECT_B.toString(), "fid-shared", null, T2);

        List<PushRegistration> rows = rowsInDb();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSubjectId()).isEqualTo(SUBJECT_B);
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_A)).isEmpty();
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_B)).containsExactly("fid-shared");
    }

    @Test
    void caseDifferingFids_areDistinctInstallations() {
        // 컬럼 단위 utf8mb4_bin: 대소문자만 다른 FID는 unique 충돌 없이 서로 다른 설치로 보존돼야 한다.
        repository.upsert(SUBJECT_A.toString(), "Fid-Case", null, T1);
        repository.upsert(SUBJECT_A.toString(), "fid-case", null, T1);

        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_A))
                .containsExactlyInAnyOrder("Fid-Case", "fid-case");
    }

    @Test
    void previousOwnerDelete_doesNotRemoveReboundRegistration() {
        // 계정 전환 뒤 이전 사용자(A)의 늦은 해제: (owner, FID) 동시 일치 조건이라 B의 등록은 남는다.
        repository.upsert(SUBJECT_A.toString(), "fid-shared", null, T1);
        repository.upsert(SUBJECT_B.toString(), "fid-shared", null, T2);

        int deleted = repository.deleteBySubjectIdAndFirebaseInstallationId(SUBJECT_A, "fid-shared");

        assertThat(deleted).isZero();
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_B)).containsExactly("fid-shared");
    }

    @Test
    void ownerDelete_isIdempotent() {
        repository.upsert(SUBJECT_A.toString(), "fid-a1", null, T1);

        assertThat(repository.deleteBySubjectIdAndFirebaseInstallationId(SUBJECT_A, "fid-a1")).isEqualTo(1);
        assertThat(repository.deleteBySubjectIdAndFirebaseInstallationId(SUBJECT_A, "fid-a1")).isZero();
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_A)).isEmpty();
    }

    @Test
    void invalidFidBatchDelete_removesOnlyGivenFids() {
        repository.upsert(SUBJECT_A.toString(), "fid-keep", null, T1);
        repository.upsert(SUBJECT_A.toString(), "fid-gone-1", null, T1);
        repository.upsert(SUBJECT_B.toString(), "fid-gone-2", null, T1);

        int deleted = repository.deleteInvalidRegistrations(List.of("fid-gone-1", "fid-gone-2"), T1);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_A)).containsExactly("fid-keep");
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_B)).isEmpty();
    }

    @Test
    void invalidFidBatchDelete_sparesRegistrationRefreshedAfterSnapshot() {
        // 발송 snapshot(T1) 이후 같은 FID가 재등록(T2)됐다면, 지연 도착한 무효 응답의 삭제가 최신 행을
        // 지우면 안 된다 — snapshot 조건부 삭제가 보호한다.
        repository.upsert(SUBJECT_A.toString(), "fid-revived", null, T1);
        repository.upsert(SUBJECT_A.toString(), "fid-revived", null, T2);

        int deleted = repository.deleteInvalidRegistrations(List.of("fid-revived"), T1);

        assertThat(deleted).isZero();
        assertThat(repository.findAllFirebaseInstallationIdsBySubjectId(SUBJECT_A)).containsExactly("fid-revived");
    }

    @Test
    void sameOptOutTokenHashOnNewFid_insertsSecondRow() {
        // FID 재발급(백업 복원·SDK 재발급)로 같은 token이 새 FID와 함께 오면 새 행이 정상 insert돼야 한다.
        // hash에 UNIQUE가 있으면 upsert가 어느 행을 갱신할지 보장되지 않아 새 FID가 저장되지 않는다.
        String hash = "a".repeat(64);
        repository.upsert(SUBJECT_A.toString(), "fid-old", hash, T1);
        repository.upsert(SUBJECT_A.toString(), "fid-new", hash, T2);

        assertThat(rowsInDb())
                .extracting(PushRegistration::getFirebaseInstallationId)
                .contains("fid-old", "fid-new");
    }

    @Test
    void reRegisteringWithoutToken_clearsStoredHash() {
        // 구버전 앱으로 되돌아간 설치는 유효하지 않은 수신거부 수단을 남기지 않는다(광고 대상에서 제외).
        repository.upsert(SUBJECT_A.toString(), "fid-tok", "b".repeat(64), T1);
        repository.upsert(SUBJECT_A.toString(), "fid-tok", null, T2);

        assertThat(rowsInDb())
                .filteredOn(row -> row.getFirebaseInstallationId().equals("fid-tok"))
                .singleElement()
                .satisfies(row -> assertThat(row.getOptOutTokenHash()).isNull());
    }
}
