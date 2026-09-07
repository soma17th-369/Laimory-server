# #432 약관 복합키 전환 runbook

`term_documents`/`term_agreements`의 인조 ID와 `effective_at`을 제거하면서 기존 문서와 동의 이력을
보존하는 maintenance cutover 절차다. `schema.sql`은 기존 DB를 변경하지 않으므로 dev/prod는 이 절차가
필요하다.

이 문서는 실행 승인이 아니다. 환경별 DB·host 변경 전에는 대상, 영향(API 중단), rollback을 다시 제시하고
명시적 승인을 받는다. 아래 이름의 shadow/legacy/failed table이 이미 있으면 재사용하지 말고 중단한다.

## 배포 게이트

- Android `develop`은 현재 `effectiveAt`을 non-null로 역직렬화한다. Android가 이 의존을 제거하고 구 build
  지원 정책이 확정되기 전에는 Server를 `dev`/`main`에 머지하거나 이 cutover를 실행하지 않는다.
- 자동 배포를 pause하고 이전/신규 Server image의 정확한 digest를 기록한다.
- cutover 동안 해당 DB를 쓰는 모든 Server를 정지한다. dev DB는 dev와 test가 공유하므로 둘 다 대상이다.
- cutover부터 rollback 창 종료까지 새 약관 version INSERT를 금지한다.

## 1. read-only preflight

MySQL 8.0.16+와 canonical source data를 확인한다. 위반 행이 한 건이라도 나오면 중단한다.
MySQL ICU의 `$`는 마지막 줄 구분자 앞에도 매칭되므로 숫자·점 외 문자도 별도로 검사한다.

```sql
SELECT VERSION();
SHOW CREATE TABLE term_documents;
SHOW CREATE TABLE term_agreements;
SELECT 'term_documents' AS table_name, COUNT(*) AS row_count FROM term_documents
UNION ALL
SELECT 'term_agreements', COUNT(*) FROM term_agreements;

SELECT term_document_id, term_type, version
FROM term_documents
WHERE CHAR_LENGTH(version) > 64
   OR version NOT REGEXP '^[1-9][0-9]*[.](0|[1-9][0-9]*)$'
   OR version REGEXP '[^0-9.]';

SELECT a.term_agreement_id, a.term_document_id
FROM term_agreements a
LEFT JOIN term_documents d ON d.term_document_id = a.term_document_id
WHERE d.term_document_id IS NULL;

SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
      'term_documents_v2_432', 'term_agreements_v2_432',
      'term_documents_legacy_432', 'term_agreements_legacy_432',
      'term_documents_failed_432', 'term_agreements_failed_432'
  );
```

`term_type`은 현재 Server의 `TermType` 6종과 대조한다. prod는 최근 logical backup과 binlog stream/upload의
복구 좌표·연속성을 확인한다. 실행 중인 deploy, `mysqldump --single-transaction`, 네 대상 table의 metadata
lock blocker/waiter가 없어야 한다.

## 2. shadow table과 rehearsal copy

구 Server가 동작하는 동안 만들 수 있지만, 이 copy는 검증 rehearsal일 뿐 cutover 데이터가 아니다.
constraint 이름은 기존 table과 database-wide 충돌하지 않도록 shadow 전용으로 둔다.

```sql
CREATE TABLE term_documents_v2_432 (
    term_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    content_url VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (term_type, version),
    CONSTRAINT chk_term_documents_v2_432_version
        CHECK (version REGEXP '^[1-9][0-9]*[.](0|[1-9][0-9]*)$'
            AND version NOT REGEXP '[^0-9.]')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE term_agreements_v2_432 (
    user_id BIGINT NOT NULL,
    term_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (user_id, term_type, version),
    KEY idx_term_agreements_v2_432_history (user_id, accepted_at, term_type, version),
    KEY idx_term_agreements_v2_432_document (term_type, version),
    CONSTRAINT fk_term_agreements_v2_432_document
        FOREIGN KEY (term_type, version)
        REFERENCES term_documents_v2_432 (term_type, version) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO term_documents_v2_432
    (term_type, version, title, content_url, created_at, updated_at, modified_by)
SELECT term_type, version, title, content_url, created_at, updated_at, modified_by
FROM term_documents;

INSERT INTO term_agreements_v2_432
    (user_id, term_type, version, accepted_at, created_at, updated_at, modified_by)
SELECT a.user_id, d.term_type, d.version, a.accepted_at, a.created_at, a.updated_at, a.modified_by
FROM term_agreements a
JOIN term_documents d ON d.term_document_id = a.term_document_id;
```

아래 검증 query는 모두 source/target count가 같고 mismatch가 0이어야 한다.

```sql
SELECT (SELECT COUNT(*) FROM term_documents) AS source_count,
       (SELECT COUNT(*) FROM term_documents_v2_432) AS target_count;
SELECT (SELECT COUNT(*) FROM term_agreements) AS source_count,
       (SELECT COUNT(*) FROM term_agreements_v2_432) AS target_count;

SELECT COUNT(*) AS document_mismatch
FROM term_documents s
LEFT JOIN term_documents_v2_432 t
  ON t.term_type = s.term_type AND t.version = s.version
WHERE t.term_type IS NULL
   OR NOT (t.title <=> s.title)
   OR NOT (t.content_url <=> s.content_url)
   OR NOT (t.created_at <=> s.created_at)
   OR NOT (t.updated_at <=> s.updated_at)
   OR NOT (t.modified_by <=> s.modified_by);

SELECT COUNT(*) AS document_reverse_mismatch
FROM term_documents_v2_432 t
LEFT JOIN term_documents s
  ON s.term_type = t.term_type AND s.version = t.version
WHERE s.term_type IS NULL
   OR NOT (s.title <=> t.title)
   OR NOT (s.content_url <=> t.content_url)
   OR NOT (s.created_at <=> t.created_at)
   OR NOT (s.updated_at <=> t.updated_at)
   OR NOT (s.modified_by <=> t.modified_by);

SELECT COUNT(*) AS agreement_mismatch
FROM term_agreements a
JOIN term_documents d ON d.term_document_id = a.term_document_id
LEFT JOIN term_agreements_v2_432 t
  ON t.user_id = a.user_id AND t.term_type = d.term_type AND t.version = d.version
WHERE t.user_id IS NULL
   OR NOT (t.accepted_at <=> a.accepted_at)
   OR NOT (t.created_at <=> a.created_at)
   OR NOT (t.updated_at <=> a.updated_at)
   OR NOT (t.modified_by <=> a.modified_by);

SELECT COUNT(*) AS agreement_reverse_mismatch
FROM term_agreements_v2_432 t
LEFT JOIN (
    SELECT a.term_agreement_id, a.user_id, d.term_type, d.version,
           a.accepted_at, a.created_at, a.updated_at, a.modified_by
    FROM term_agreements a
    JOIN term_documents d ON d.term_document_id = a.term_document_id
) s
  ON s.user_id = t.user_id AND s.term_type = t.term_type AND s.version = t.version
WHERE s.term_agreement_id IS NULL
   OR NOT (s.accepted_at <=> t.accepted_at)
   OR NOT (s.created_at <=> t.created_at)
   OR NOT (s.updated_at <=> t.updated_at)
   OR NOT (s.modified_by <=> t.modified_by);
```

`SHOW CREATE TABLE`로 CHECK/FK가 실제로 생성됐는지 확인한다.

## 3. maintenance final copy와 atomic swap

1. 모든 application process를 정지하고 writer connection/transaction 0을 확인한다.
2. prod는 실행 중 dump와 metadata-lock blocker/waiter 0, binlog stream health를 다시 확인한다.
3. rehearsal target을 child→parent 순서로 비우고 parent→child 순서로 source를 최종 복사한다.

```sql
DELETE FROM term_agreements_v2_432;
DELETE FROM term_documents_v2_432;

INSERT INTO term_documents_v2_432
    (term_type, version, title, content_url, created_at, updated_at, modified_by)
SELECT term_type, version, title, content_url, created_at, updated_at, modified_by
FROM term_documents;

INSERT INTO term_agreements_v2_432
    (user_id, term_type, version, accepted_at, created_at, updated_at, modified_by)
SELECT a.user_id, d.term_type, d.version, a.accepted_at, a.created_at, a.updated_at, a.modified_by
FROM term_agreements a
JOIN term_documents d ON d.term_document_id = a.term_document_id;
```

rehearsal과 같은 count·양방향 anti-join/value 검증을 다시 통과한 뒤 한 문장으로 swap한다.

```sql
RENAME TABLE
    term_agreements TO term_agreements_legacy_432,
    term_documents TO term_documents_legacy_432,
    term_documents_v2_432 TO term_documents,
    term_agreements_v2_432 TO term_agreements;
```

`SHOW CREATE TABLE`과 `information_schema.KEY_COLUMN_USAGE`로 새 agreement FK가 새 `term_documents`를
가리키는지 확인한다. 그 뒤에만 신규 image digest를 기동하고 health, public terms, initializer,
동의 POST/재POST, history, stale 409를 smoke한다.

## 4. rollback

legacy table은 관찰 기간 종료 승인 전까지 보존한다. rollback 전 모든 Server를 다시 정지한다. 신규 schema의
계정 삭제와 동의를 legacy에 반영하며, 새 version INSERT 금지 규칙 때문에 모든 document key가 legacy에
존재해야 한다.

```sql
-- 새 schema에서 삭제된 회원 동의를 legacy에서도 삭제한다.
DELETE la
FROM term_agreements_legacy_432 la
JOIN term_documents_legacy_432 ld ON ld.term_document_id = la.term_document_id
LEFT JOIN term_agreements na
  ON na.user_id = la.user_id AND na.term_type = ld.term_type AND na.version = ld.version
WHERE na.user_id IS NULL;

-- cutover 뒤 새로 생긴 동의를 legacy document ID로 역매핑한다.
INSERT INTO term_agreements_legacy_432
    (user_id, term_document_id, accepted_at, created_at, updated_at, modified_by)
SELECT na.user_id, ld.term_document_id, na.accepted_at, na.created_at, na.updated_at, na.modified_by
FROM term_agreements na
JOIN term_documents_legacy_432 ld
  ON ld.term_type = na.term_type AND ld.version = na.version
LEFT JOIN term_agreements_legacy_432 la
  ON la.user_id = na.user_id AND la.term_document_id = ld.term_document_id
WHERE la.term_agreement_id IS NULL;
```

agreement 양방향 equivalence와 legacy FK를 확인한 뒤 atomic rename으로 복원한다.

```sql
RENAME TABLE
    term_agreements TO term_agreements_failed_432,
    term_documents TO term_documents_failed_432,
    term_documents_legacy_432 TO term_documents,
    term_agreements_legacy_432 TO term_agreements;
```

old schema와 row count를 확인한 뒤 이전 image digest를 기동한다. Server rollback은 `effectiveAt` 응답도
복원하므로 Android 양쪽 contract를 smoke한 뒤 traffic을 재개한다.

## 5. rollback 창 종료

go/no-go 승인 뒤에만 legacy child→parent를 삭제한다. shadow 전용 FK/CHECK/index 이름을 fresh
`schema.sql`의 이름으로 맞추려면 legacy 삭제 후 별도 `ALTER TABLE`로 교체하고 다시 `SHOW CREATE TABLE`을
대조한다. prod backup/binlog가 새 schema 이후 정상 복구 좌표를 남긴 것도 확인한다. legacy/failed table
삭제는 복구 불가능한 정리이므로 별도 승인을 받아 실행한다.

```sql
DROP TABLE term_agreements_legacy_432;
DROP TABLE term_documents_legacy_432;

ALTER TABLE term_agreements
    DROP FOREIGN KEY fk_term_agreements_v2_432_document,
    RENAME INDEX idx_term_agreements_v2_432_history TO idx_term_agreements_user_history,
    RENAME INDEX idx_term_agreements_v2_432_document TO idx_term_agreements_document,
    ADD CONSTRAINT fk_term_agreements_document
        FOREIGN KEY (term_type, version)
        REFERENCES term_documents (term_type, version) ON DELETE RESTRICT;

ALTER TABLE term_documents
    DROP CHECK chk_term_documents_v2_432_version,
    ADD CONSTRAINT chk_term_documents_version_canonical
        CHECK (version REGEXP '^[1-9][0-9]*[.](0|[1-9][0-9]*)$'
            AND version NOT REGEXP '[^0-9.]');
```
