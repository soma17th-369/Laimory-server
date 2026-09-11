# Flyway 최초 편입과 스키마 변경

Spring Boot 3.5.8이 관리하는 Flyway 11.7.2를 사용한다. 실행 가능한 스키마 원천은
`src/main/resources/db/migration`의 버전 SQL이다. 배포한 SQL은 수정하지 않고 다음 버전을 추가한다.
V1은 도입 시점의 업무 테이블 17개와 신규 DB에 필요한 `app_config` 한 행을 생성한다.

## 실행 주체

| 환경 | 실행 |
|---|---|
| local / CI (`docker` profile) | 앱 시작 시 Flyway migrate → Hibernate validate |
| dev / prod / test (기본 profile) | 앱 Flyway off. 승인된 CLI 실행 → 검증 → 기존 앱 배포 |

`ddl-auto=validate`는 모든 환경에서 유지한다. Compose는 MySQL DB/사용자만 준비하며 schema init SQL을
mount하지 않는다. 기존 volume은 자동 초기화/삭제하지 않는다. `spring.sql.init.mode=never`,
`baseline-on-migrate=false`, `clean-disabled=true`가 공통 정책이다.

기본 profile은 Flyway 자체의 이력 검증도 실행하지 않는다. `deploy.yml`의 subject schema와
`app_config` 검사 및 health gate는 유지되지만 Flyway pending/checksum을 검사하지는 않는다.
따라서 아래 CLI 확인은 운영자가 수행하는 배포 전 절차다.

## 1. 실행 대상과 권한 확인

AWS 조사는 먼저 `sandbox` SSO를 확인한 뒤 조회·SSM 비변경 진단으로 시작한다. DB/host 변경은 대상,
영향, rollback을 제시하고 명시적 승인을 받는다. 이 문서가 DB 변경 승인을 대신하지 않는다.

- 실제 endpoint와 DB 이름, MySQL 버전, 현재 앱 revision을 확인한다. 저장소 설정만으로 live 상태를 단정하지 않는다.
- dev와 test는 같은 DB를 공유하므로 baseline과 migration 이력도 하나다. prod DB는 별도다.
- CLI 계정은 대상 schema의 필요한 CREATE/ALTER/INDEX/REFERENCES 및 history·업무 SQL에 필요한 DML 권한을
  확인한다. DROP 등 추가 권한은 실제 SQL이 요구할 때만 검토한다. 앱 계정 권한을 임의로 넓히지 않는다.
- 운영에 `SPRING_FLYWAY_ENABLED=true`나 `docker` profile이 설정돼 있지 않은지 확인한다.
- 스키마 변경 배포는 머지 전에 기존 `DEPLOY_PAUSED`를 사용하고 진행 중인 배포가 없는지 확인한다.
  pause는 이미 시작한 run을 멈추지 않는다. 검증 뒤 승인된 SHA/digest로 기존 수동 deploy-existing을 실행한다.

## 2. 같은 버전 CLI와 승인된 SQL 준비

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

과거 수동 DDL이나 약관 전환이 남아 있으면 해당 변경의 기존 runbook으로 먼저 해결한다. 이 작업에 무관한
legacy 테이블 삭제나 데이터 보정을 섞지 않는다. 제약 이름 차이는 이후 migration이 참조할 이름을 확인한 뒤
기준을 확정한다. 차이가 해소되지 않은 DB를 V1과 같은 것으로 등록하지 않는다.

### 명시적 baseline

대상 DB가 V1 상태임을 확인하고 최근 backup/복구 가능 여부, 적용 대상과 영향을 확인한 뒤 승인받아 실행한다.

```bash
flyway -baselineVersion=1 baseline
flyway info
flyway validate
flyway migrate
flyway info
```

history에는 `BASELINE` 버전 1이 기록되어야 한다. V1의 CREATE/INSERT는 실행하지 않는다. 이 도입 release에
후속 migration이 없다면 migrate는 변경 없이 종료한다. `baseline`은 빠진 컬럼을 보정하거나 기존 데이터가
올바른지 검증하는 기능이 아니다. 변경 전후 데이터·운영 설정 보존과 failed/pending 없음 확인 뒤 앱을 배포한다.

기존 local volume도 동일하다. `docker compose up -d`로 기존 DB를 기동하고 구조 대조 후 명시 baseline한다.
데이터를 버려도 된다는 사용자 선택이 있을 때만 별도로 초기화한다. 테스트 script는 기존 volume을 사용하지 않는다.

## 4. 빈 DB 및 이후 변경

빈 DB는 baseline 없이 `flyway migrate`로 V1부터 실행한다. baseline을 먼저 하면 V1이 생략된다.
신규 운영 DB는 앱 배포의 subject schema/`app_config` preflight **전에** CLI 초기화를 끝내야 한다.

이후 SQL은 `V2__description.sql`, `V3__description.sql`로 추가한다. 배포된 파일은 불변이며 과거 오류도
새 버전으로 고친다. 같은 번호를 쓴 PR끼리는 어느 공유 DB에도 실행되기 전에 번호를 조정한다.
dev/test는 동일한 migration 집합을 사용하고, test에서 공유 DB의 독자적인 schema 실험을 하지 않는다.

```bash
flyway info
# 대상·변경 SQL을 확인하고 실행 승인 후
flyway migrate
flyway validate
flyway info
```

failed/pending이 없는지 확인한 후 SQL과 같은 commit의 앱 image를 기존 배포 절차로 실행한다.
nullable 컬럼 추가 등 구 앱과 호환되는 변경은 선적용할 수 있다. 삭제/rename 등은 구 앱이 사용하는
schema를 깨므로 단계적으로 전환하거나, 그 DB를 쓰는 모든 서버를 중단하는 maintenance 순서를 별도로 정한다.
마이그레이션 동시 실행 잠금이 구 앱과 새 schema의 호환성을 보장하지는 않는다.

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

첫 script는 빌드된 앱 JAR의 JDBC를 사용해 독립 MySQL에서 신규 생성, 이전 스키마와 DDL 동일성, 재실행, 명시 baseline과 합성 데이터 보존,
자동 baseline 거부 및 checksum 불일치를 검증하고 만든 리소스만 정리한다.
`src/test/resources/db/legacy/pre-flyway-schema.sql`은 도입 직전 스냅샷으로 동결한다. 운영 초기화에 쓰거나
이후 스키마에 맞춰 갱신하지 않는다. 실제 V2부터는 해당 변경에 이전 버전과 대표 데이터를 최신으로 올리는
업그레이드 검증을 추가한다. CI의 앱 DB는 Spring Flyway가 생성하고 기존 `/intro`·JPA 통합 테스트가 검증한다.

## 근거

- [Spring Boot 3.5 초기화](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)
- [Flyway baseline](https://documentation.red-gate.com/fd/baseline-277578867.html)
- [버전 SQL 관리](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)
- [MySQL 트랜잭션 한계](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)
