# Codebase Constraints

## Scope

설계 대안을 고를 때 먼저 확인해야 하는 현재 기술·운영 제약이다.

## Read When

dependency, schema, Redis, profile, AI, logging, Docker, deployment 또는 AWS 운영 경계를 바꿀 때 읽는다.

## Authoritative Sources

- `build.gradle`, `application*.properties`, `schema.sql`
- `docker-compose.yml`, `Dockerfile`
- `.github/workflows/*.yml`
- live AWS, GitHub repository Variables와 host 상태
- architecture and integration tests

## Current Constraints

- Java 21, Spring Boot 3.5.8과 repository의 Gradle Wrapper를 사용한다.
- persistence runtime은 MySQL 8과 Redis 7이다.
- Hibernate는 `ddl-auto=validate`이며 schema를 자동 생성·변경하지 않는다.
- Flyway/Liquibase가 없어 기존 DB schema 변경에는 수동 DDL과 rollout 순서가 필요하다.
- Compose의 `schema.sql`은 빈 MySQL volume 최초 초기화에만 적용된다.
- application-owned Redis 접근은 `RedisGateway`를 거쳐야 한다.
- dev와 prod가 Redis를 공유하므로 dev는 환경 prefix로 격리한다.
- 기본 Spring profile은 원격 의존성을 기대한다. 로컬 실행은 `docker` profile을 사용한다.
- DB·Redis·JWT·OAuth의 필수 설정 일부는 startup에서 fail-fast한다.
- subject 매핑 HMAC key(#282)의 `app.subject.mode`는 배포 기본 프로필에 기본값이 없어 미설정이면
  기동 실패한다. provider 선택은 mode property 단일 축이다(`@Profile` 게이팅 없음) — fixture-key
  기본값은 docker profile만 소유해 배포 기본 프로필의 fixture mode는 무기본값 property로 기동
  실패하고, deploy preflight가 `APP_SUBJECT_MODE=secretsmanager` 값 고정과
  `SPRING_PROFILES_ACTIVE`의 docker 포함 금지를 함께 강제한다. `secretsmanager` 모드는 기동 시 Secrets Manager
  `GetSecretValue`를 정확히 1회 호출해 32-byte key·version schema 검증 실패 시 기동을 실패시킨다 —
  이 저장소에서 유일하게 context refresh 중 실 AWS를 호출하는 빈이며(S3 client는 생성 시점 무호출),
  요청 경로 재호출은 없고 rotation 반영은 secret 갱신 + 재기동이다. AWS SDK `secretsmanager` 모듈은
  기존 BOM으로 버전을 관리한다.
- Swagger는 기본 off이고 dev/local에서만 켠다.
- AI mode는 현재 `noop|fake`뿐이며 production dispatcher가 없다.
- 기본 profile은 JSON stdout, local docker profile은 text log를 사용한다.
- Actuator는 app port와 분리된 9090에서 health·Prometheus endpoint만 노출한다. 애플리케이션 인증이
  아니라 private network와 source-limited SG가 접근 경계다.
- 허용된 JSON request/response body만 512 KiB 제한 캡처 후 text preview로 log에 남긴다.
  query string, 민감 header, token·credential·presigned URL 원문은 남기지 않으며, 사용자 사생활 원문을
  통째로 담는 지정 timeline·AI endpoint의 body는 고정 placeholder로 전체 마스킹한다
  (목록은 observability).
- Docker image build는 test를 제외하며 PR CI가 `./gradlew build`를 담당한다.
- 자동 애플리케이션 배포는 dev의 image/deploy 관련 path에만 실행되며 health failure 자동 rollback은 없다.
- dev monitoring alert rule은 관련 path merge에서만 S3 release와 SSM 적용이 자동 실행되며,
  dashboard·collector 등 나머지 monitoring 자산은 이 자동 배포 범위가 아니다.
- 저장소는 전체 AWS topology나 신규 host 초기화를 자동화하지 않는다.
- live AWS, GitHub repository Variables와 실제 host 상태가 운영 구성의 권위 원천이다.
- AWS 작업은 먼저 `sandbox` SSO, 조회와 SSM 비변경 진단으로 제한하고 수정은 별도 승인받는다.
- monitoring bootstrap S3에는 비밀 없는 자산만 두며 Grafana/exporter/datasource/Discord secret은
  Session Manager로 host의 보호된 파일에만 주입한다.

## Invariants

- secret·credential 값은 knowledge에 복제하지 않는다.
- schema와 entity는 함께 변경하되, 실행 중인 DB 반영 절차를 별도로 계획한다.
- 배포·인프라 설명은 의도와 현재 automation을 분리한다.

## Known Gaps

- migration framework, production deploy workflow, application automatic rollback, distributed tracing과 완성된
  live monitoring/alerting rollout이 없다.

## Update When

버전, storage engine, profile 기본값, enforced architecture rule, CI/deploy/infra 제약이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose config --quiet
```
