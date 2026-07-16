# Codebase Constraints

## Scope

설계 대안을 고를 때 먼저 확인해야 하는 현재 기술·운영 제약이다.

## Read When

dependency, schema, Redis, profile, AI, logging, Docker, deployment 또는 Terraform을 바꿀 때 읽는다.

## Authoritative Sources

- `build.gradle`, `application*.properties`, `schema.sql`
- `docker-compose.yml`, `Dockerfile`
- `.github/workflows/*.yml`
- `terraform/*.tf`, `terraform/user_data/*.tftpl`
- architecture and integration tests

## Current Constraints

- Java 21, Spring Boot 3.5.8과 repository의 Gradle Wrapper를 사용한다.
- persistence runtime은 MySQL 8과 Redis 7이다.
- Hibernate는 `ddl-auto=validate`이며 schema를 자동 생성·변경하지 않는다.
- Flyway/Liquibase가 없어 기존 DB schema 변경에는 수동 DDL과 rollout 순서가 필요하다.
- Compose의 `schema.sql`은 빈 MySQL volume 최초 초기화에만 적용된다.
- application-owned Redis 접근은 `PrefixedRedis`를 거쳐야 한다.
- dev와 prod가 Redis를 공유하므로 dev는 환경 prefix로 격리한다.
- 기본 Spring profile은 원격 의존성을 기대한다. 로컬 실행은 `docker` profile을 사용한다.
- DB·Redis·JWT·OAuth의 필수 설정 일부는 startup에서 fail-fast한다.
- Swagger는 기본 off이고 dev/local에서만 켠다.
- AI mode는 현재 `noop|fake`뿐이며 production dispatcher가 없다.
- 기본 profile은 JSON stdout, local docker profile은 text log를 사용한다.
- 허용된 JSON request/response body만 64 KiB 제한 캡처 후 text preview로 log에 남긴다.
  query string, 민감 header, token·credential·presigned URL 원문은 남기지 않는다.
- Docker image build는 test를 제외하며 PR CI가 `./gradlew build`를 담당한다.
- 자동 애플리케이션 배포는 dev만 있고 health failure 자동 rollback은 없다.
- 기존 WAS/MySQL/ELK EC2의 `user_data` 변경은 lifecycle ignore 때문에 live 반영되지 않는다.
- Terraform state는 local이며 secret을 포함할 수 있어 commit하지 않는다.
- AWS Sandbox의 Terraform은 재구성 recipe다. live 환경에 blanket apply하지 않고 plan을 사람이 검토한다.

## Invariants

- secret·credential 값은 knowledge에 복제하지 않는다.
- schema와 entity는 함께 변경하되, 실행 중인 DB 반영 절차를 별도로 계획한다.
- 배포·인프라 설명은 의도와 현재 automation을 분리한다.

## Known Gaps

- migration framework, production deploy workflow, automatic rollback, metrics/tracing/alerting이 없다.

## Update When

버전, storage engine, profile 기본값, enforced architecture rule, CI/deploy/infra 제약이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose config --quiet
terraform fmt -check -recursive terraform
```
