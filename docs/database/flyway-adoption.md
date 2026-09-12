# Flyway 자동 실행과 기존 DB 편입

Spring Boot 3.5.8이 관리하는 Flyway 11.7.2를 사용한다. 실행 가능한 스키마 원천은
`src/main/resources/db/migration`의 버전 SQL이다. 배포한 SQL은 수정하지 않고 다음 버전을 추가한다.
V1은 도입 시점의 업무 테이블 17개와 신규 DB에 필요한 `app_config` 한 행을 생성한다.
약관 index/FK/CHECK는 #432 cutover를 완료한 기존 DB의 이름을 사용한다. 신규 DB와 baseline한
기존 DB에서 이후 migration이 같은 이름을 참조하도록 맞춘 도입 전 결정이며, 배포한 V1을 수정하는 절차가 아니다.

## 실행 주체

| 환경 | 실행 |
|---|---|
| local / CI / dev / prod / test | 앱 시작 시 Flyway migrate → Hibernate validate → 서버 기동 |
| 기존 DB 최초 편입 / 빈 운영 DB 준비 | 승인된 CLI로 baseline / bootstrap 후 앱 배포 |

`ddl-auto=validate`는 모든 환경에서 유지한다. Compose는 MySQL DB/사용자만 준비하며 schema init SQL을
mount하지 않는다. 기존 volume은 자동 초기화/삭제하지 않는다. `spring.sql.init.mode=never`,
`spring.flyway.enabled=true`, `baseline-on-migrate=false`, `clean-disabled=true`가 공통 정책이다.
앱은 기존 DataSource를 사용한다. Flyway를 켜려고 앱 `.env`에 새 항목을 추가할 필요는 없다.
적용한 migration의 checksum 검증과 미적용 SQL 실행이 실패하면 앱도 기동에 실패한다.

### 여러 서버와 배포 순서

- 같은 DB를 쓰는 서버는 같은 기본 `flyway_schema_history`와 migration 집합을 사용한다.
  Flyway 11.7.2는 MySQL named lock으로 실행을 조정한다. 동시 시작 시 잠금을 기다린 프로세스는
  이력을 다시 확인하여 이미 적용된 SQL을 건너뛴다. 앱 별도 lock이나 leader 선출은 두지 않는다.
- 기존 `deploy.yml`은 host를 한 대씩 교체하고 health 성공 후 다음으로 진행한다. 실패하면 남은 host는
  교체하지 않는다. ALB target 해제/재등록은 없어 무중단 배포를 보장하지 않는다.
- 한 서버가 migration을 실행하는 동안 다른 서버는 구 앱을 실행할 수 있다. SQL은 구 앱과 호환되어야 한다.
  컬럼 삭제/rename은 새 컬럼 추가 → 양쪽 호환 코드 전환 → 구 앱 제거 → 후속 삭제처럼 단계적으로 진행한다.
- dev/test는 DB를 공유하므로 test 브랜치의 독자 migration은 금지한다. dev에서 적용·검증한 동일 SQL을
  test에도 사용하고, 뒤처진 test 앱도 새 schema와 호환되는지 확인한다.
- 현재 health 대기는 90초이며 migration과 잠금 대기도 이 시간에 포함된다. 오래 걸리는 DDL/대량 갱신은
  평소 앱 시작에 묶지 않고 승인된 별도 작업으로 수행한다. health timeout은 DB 작업 취소/원복이 아니다.

`deploy.yml`의 앱 시작 전 subject schema와 `app_config` 검사는 유지한다. **빈 운영 DB는 이 검사 전에**
아래 CLI bootstrap을 완료한다. 향후 이 검사 대상 테이블을 변경하는 PR은 배포 중 구/신 schema 양쪽에서
preflight가 유효하도록 함께 조정해야 한다. 이번 V1은 업무 DDL을 바꾸지 않아 기존 검사를 통과한다.

## 1. 실행 대상과 권한 확인

AWS 조사는 먼저 `sandbox` SSO를 확인한 뒤 조회·SSM 비변경 진단으로 시작한다. DB/host 변경은 대상,
영향, rollback을 제시하고 명시적 승인을 받는다. 이 문서가 DB 변경 승인을 대신하지 않는다.

- 실제 endpoint와 DB 이름, MySQL 버전, 현재 앱 revision을 확인한다. 저장소 설정만으로 live 상태를 단정하지 않는다.
- dev와 test는 같은 DB를 공유하므로 baseline과 migration 이력도 하나다. prod DB는 별도다.
- 앱 DataSource 계정에는 대상 schema의 migration/history에 필요한 CREATE/ALTER/INDEX/REFERENCES 및
  SELECT/DML 권한이 필요하다. 실제 권한을 조회하고, 부족한 권한은 대상과 영향을 제시한 별도 승인 후 부여한다.
  DROP 등은 실행할 SQL이 요구할 때만 검토한다. CLI 계정도 수행할 baseline/bootstrap 권한을 확인한다.
- 앱 `.env`에 자동 실행을 끄는 `SPRING_FLYWAY_ENABLED=false` override가 없는지 확인한다.
  운영에서 `docker` profile은 여전히 금지한다(로컬 DB/fixture 설정이 함께 켜진다).
- 최초 편입 또는 maintenance는 머지 전에 기존 `DEPLOY_PAUSED`를 사용하고 진행 중인 배포가 없는지 확인한다.
  pause는 이미 시작한 run을 멈추지 않는다. 편입 완료 후 승인된 SHA/digest를 배포하고 자동 배포를 재개한다.

## 2. 최초 편입용 CLI와 승인된 SQL 준비

이 CLI 준비는 최초 baseline/빈 운영 DB bootstrap 또는 별도로 승인한 maintenance에 필요하다.
일반 배포에서는 앱이 migration을 실행하므로 매번 CLI를 실행하지 않는다.

테스트와 운영 CLI는 다음 공식 이미지로 고정한다. 공식 이미지에는 MariaDB JDBC 2.7.11이 들어 있으므로
앱 JAR의 MySQL Connector/J를 추가 mount하고 드라이버를 명시한다. MySQL 연결 및 아래 baseline/migrate/validate
경로는 `.github/scripts/test-flyway-migrations.sh`가 같은 이미지·앱 JDBC로 검증한다.

```bash
FLYWAY_IMAGE=flyway/flyway:11.7.2-alpine@sha256:a493a5ef0700f6d1ef4f7b83320f79601071b20749c2eca1d73dd2e352948656
docker pull "$FLYWAY_IMAGE"
docker run --rm "$FLYWAY_IMAGE" -v
```

Boot/Flyway를 업그레이드할 때는 앱 의존성과 이 이미지, 테스트 및 이 문서를 함께 갱신한다.

배포 대상의 40자리 commit SHA와 ECR digest를 기존 배포 절차로 확인한다. 그 image에서 앱 JAR을 꺼내
SQL과 JDBC 드라이버를 함께 추출한다. 아래 `docker create`는 컨테이너를 시작하지 않으므로 앱이나 worker가
실행되지 않는다. 현재 checkout의 수정 중인 SQL이나 test 브랜치 전용 SQL을 공유 DB에 실행하지 않는다.

```bash
# APP_IMAGE에는 승인된 ECR image의 digest reference(repository@sha256:...)를 설정한다.
docker pull "$APP_IMAGE"
SQL_WORK=$(mktemp -d)
SQL_CONTAINER=$(docker create "$APP_IMAGE")
docker cp "$SQL_CONTAINER:/app/app.jar" "$SQL_WORK/app.jar"
docker rm "$SQL_CONTAINER"
unzip -q "$SQL_WORK/app.jar" 'BOOT-INF/classes/db/migration/*.sql' -d "$SQL_WORK"
unzip -p "$SQL_WORK/app.jar" 'BOOT-INF/lib/mysql-connector-j-*.jar' >"$SQL_WORK/mysql-connector-j.jar"
FLYWAY_SQL_DIR="$SQL_WORK/BOOT-INF/classes/db/migration"
FLYWAY_JDBC_JAR="$SQL_WORK/mysql-connector-j.jar"
shasum -a 256 "$FLYWAY_SQL_DIR/"*.sql
```

DB에 접근 가능한 승인된 관리 host에서 실행한다. 로컬에서 직접 private DB에 접근할 수 있다고 가정하지
않는다. 다른 host로 추출물을 전달하면 승인된 경로를 사용하고 SQL/JDBC 파일의 SHA-256이 같은지 확인한다.
접속 정보는 Git 밖의 접근 제한된 파일(0600)에 아래 세 환경변수만 준비한다. 예시의 꺾쇠 부분은 실제
확인한 값으로 채우되 파일 내용·비밀번호를 로그나 CLI 인자로 출력하지 않는다. 앱 `.env` 전체를 넘기지 않는다.

```text
FLYWAY_URL=jdbc:mysql://<approved-db-host>:3306/laimory?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
FLYWAY_USER=<approved-migration-user>
FLYWAY_PASSWORD=<secret>
```

Linux 관리 host에서 private DB에 연결할 때는 `FLYWAY_NETWORK=host`를 사용한다. 로컬 Compose DB에
편입할 때는 `FLYWAY_NETWORK=container:laimory-mysql`과 URL host `127.0.0.1`을 사용한다.
서로 다른 DB/계정의 파일을 재사용하지 말고 baseline 전에 `SELECT DATABASE(), VERSION()` 및 테이블 목록으로
대상을 다시 확인한다. `FLYWAY_ENV_FILE`은 준비한 파일의 절대 경로다.

```bash
flyway() {
  docker run --rm --network "$FLYWAY_NETWORK" \
    --env-file "$FLYWAY_ENV_FILE" \
    -v "$FLYWAY_SQL_DIR:/flyway/sql:ro" \
    -v "$FLYWAY_JDBC_JAR:/flyway/drivers/mysql-connector-j.jar:ro" "$FLYWAY_IMAGE" \
    -driver=com.mysql.cj.jdbc.Driver -locations=filesystem:/flyway/sql \
    -baselineOnMigrate=false -cleanDisabled=true \
    -outOfOrder=false -validateOnMigrate=true "$@"
}
flyway info
```

CLI는 DB 접속 주소와 오류 SQL을 출력할 수 있으므로 출력은 접근 제한된 운영 기록으로 취급한다.
실제 행이나 접속 정보가 담긴 결과를 공개 Actions/이슈에 붙이지 않는다.

## 3. 기존 DB를 처음 편입할 때

### 스키마 대조

먼저 Flyway 이력 테이블이 없는지 확인한다. 이미 있으면 재baseline하거나 삭제하지 않고 이력을 조사한다.

V1로 생성한 임시 DB와 대상 DB의 `SHOW CREATE TABLE`/`information_schema`를 대조한다. 확인 대상은
업무 테이블·컬럼 타입/nullable/default/collation, PK/UK/index, FK 및 CHECK다. `ddl-auto=validate`만으로
인덱스·제약 전체 일치를 증명하지 않는다. AUTO_INCREMENT의 현재 counter처럼 데이터에 따라 달라지는 값은
구조 차이와 구분한다. `app_config`가 정확히 한 행인지도 확인한다.

약관 전환 후 유지할 이름은 다음과 같다. 이 이름들은 V1과 일치해야 한다.

| 객체 | 이름 |
|---|---|
| 동의 이력 index | `idx_term_agreements_v2_432_history` |
| 문서 참조 index | `idx_term_agreements_v2_432_document` |
| 문서 참조 FK | `fk_term_agreements_v2_432_document` |
| 버전 형식 CHECK | `chk_term_documents_v2_432_version` |

#432의 rollback용 `term_agreements_legacy_432`/`term_documents_legacy_432`는 업무 테이블 17개와
구분하여 보존한다. 활성 테이블이 legacy를 참조하지 않는지 확인하며 baseline을 위해 삭제하지 않는다.
컬럼의 물리적 순서만 다른 것은 이름을 명시하는 앱 SQL 계약의 구조 차이가 아니다. 컬럼 정의와
복합 index/FK 내부의 컬럼 순서는 그대로 대조한다. 그 밖의 테이블/구조 차이는 개별 조사한다.

과거 수동 DDL이나 약관 전환이 남아 있으면 해당 변경의 기존 runbook으로 먼저 해결한다. 이 작업에 무관한
legacy 테이블 삭제나 데이터 보정을 섞지 않는다. 제약 이름 차이는 이후 migration이 참조할 이름을 확인한 뒤
기준을 확정한다. 차이가 해소되지 않은 DB를 V1과 같은 것으로 등록하지 않는다.

### 명시적 baseline

대상 DB가 V1 상태임을 확인하고 최근 backup/복구 가능 여부, 적용 대상과 영향을 확인한 뒤 승인받아 실행한다.

```bash
flyway -baselineVersion=1 baseline
flyway -target=1 validate
flyway info
```

history에는 `BASELINE` 버전 1이 기록되어야 한다. V1의 CREATE/INSERT는 실행하지 않는다.
`baseline`은 빠진 컬럼을 보정하거나 기존 데이터가 올바른지 검증하는 기능이 아니다.
변경 전후 데이터·운영 설정 보존과 이력을 확인한 뒤 앱을 배포한다. 현재 V1 도입 release에는 후속 SQL이 없어
앱의 migrate는 변경 없이 끝난다. 이후 버전은 앱이 자동 실행한다. `target=1`은 최초 편입 확인에만 사용한다.

기존 local volume도 동일하다. `docker compose up -d`로 기존 DB를 기동하고 구조 대조 후 명시 baseline한다.
데이터를 버려도 된다는 사용자 선택이 있을 때만 별도로 초기화한다. 테스트 script는 기존 volume을 사용하지 않는다.

## 4. 빈 DB 및 이후 변경

빈 local/CI DB는 앱 시작 시 V1부터 실행한다. baseline을 먼저 하면 V1이 생략되므로 등록하지 않는다.
신규 운영 DB는 subject schema/`app_config` preflight **전에** 아래 CLI 초기화를 한 번 완료한다.

```bash
# 빈 운영 DB의 최초 bootstrap: 대상·SQL 확인과 실행 승인 후
flyway migrate
flyway validate
flyway info
```

이후 일반 변경은 다음 순서다.

1. `V2__description.sql`, `V3__description.sql`을 추가하고 구 앱과 호환되는지 확인한다.
2. CI에서 빈 DB와 이전 버전 + 대표 데이터의 업그레이드를 검증한다.
3. 기존 배포로 새 앱을 기동한다. Flyway가 checksum 검증과 미적용 SQL을 실행하고 JPA가 검증한다.
4. health 성공 후 다음 host로 진행하고, 모든 host의 성공 및 migration 이력을 확인한다.

배포된 SQL은 수정하지 않고 새 버전으로 고친다. 같은 번호를 사용한 PR끼리는 어느 공유 DB에도 실행되기
전에 번호를 조정한다. 잠금은 중복 실행을 방지하지만 구 앱과 새 schema의 호환성을 보장하지 않는다.

## 5. 실패와 복구

- baseline만 완료한 최초 도입은 업무 schema를 바꾸지 않는다. 이전 앱 image로 돌아가더라도 history를 보존한다.
- MySQL DDL은 여러 SQL 전체의 rollback을 보장하지 않는다. 실패하면 앱 배포를 중지하고 실제 적용된 DDL과
  데이터를 확인한 뒤 수정 SQL 또는 backup 복구를 승인받아 실행한다.
- `repair`는 적용된 DDL을 되돌리지 않는다. 실제 상태와 script를 맞추기 전에 실패 기록만 삭제하지 않는다.
- `clean`, 자동 baseline, 자동 repair는 정상 배포/복구 경로에 넣지 않는다.
- 기존 배포의 이전 image 재실행은 DB 변경을 취소하지 않는다. DB 변경 후 구 image의 호환성도 확인한다.

## 6. 검증

```bash
./gradlew bootJar
bash .github/scripts/test-flyway-migrations.sh
docker compose up -d --wait
./gradlew build integrationTest jacocoAllTestReport
```

첫 script는 앱 JAR의 JDBC로 독립 MySQL에서 두 프로세스의 최초 생성, 이전 스키마와 DDL 동일성, 재실행,
명시 baseline과 데이터 보존, 자동 baseline 거부, checksum 불일치를 검증한다. 최초 편입 검증은 V1에 고정한다.
동결한 이전 SQL fixture의 약관 이름 네 개만 위 기준으로 치환한 사본을 사용하며, 이외 DDL은 전체 비교한다.
임시 V2에서는 첫 migration의 실행과 둘째의 native lock 대기를 겹치게 한 뒤, 이력/결과 한 건과 양쪽 성공을
확인한다. 테스트용 V2는 앱에 포함되지 않는다. 만든 컨테이너/네트워크만 정리한다.
`src/test/resources/db/legacy/pre-flyway-schema.sql`은 도입 직전 스냅샷으로 동결한다. 운영 초기화에 쓰거나
이후 스키마에 맞춰 갱신하지 않는다. 실제 V2부터는 해당 변경에 이전 버전과 대표 데이터를 최신으로 올리는
업그레이드 검증을 추가한다. CI의 앱 DB는 Spring Flyway가 생성하고 기존 `/intro`·JPA 통합 테스트가 검증한다.

## 근거

- [Spring Boot 3.5 초기화](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)
- [Flyway 11.7.2 MySQL 잠금](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLNamedLockTemplate.java)
- [Flyway baseline](https://documentation.red-gate.com/fd/baseline-277578867.html)
- [버전 SQL 관리](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)
- [MySQL 트랜잭션 한계](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)
