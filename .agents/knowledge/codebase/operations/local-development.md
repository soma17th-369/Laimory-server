# Local Development

## Scope

로컬 MySQL·Redis와 Spring Boot 서버를 실행하고 기본 상태를 확인하는 절차다.

## Read When

로컬 서버 실행, Docker dependency, Swagger 또는 profile 문제를 다룰 때 읽는다.

## Authoritative Sources

- `build.gradle`, Gradle Wrapper
- `docker-compose.yml`
- `application.properties`, `application-docker.properties`
- `logback-spring.xml`, photo storage config

## Current Procedure

Java 21을 준비하고 repository root에서 실행한다.

```bash
docker compose up -d
docker compose ps
SPRING_PROFILES_ACTIVE=docker ./gradlew bootRun
```

확인:

```bash
curl -i http://localhost:8080/status
```

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

docker profile은 local MySQL 8·Redis 7, Swagger, dummy JWT/OAuth boot 설정과 text logging을 사용한다.
AI와 geo는 별도 override가 없으면 noop이다.

## Constraints

- 기본 profile은 remote DB/Redis와 필수 JWT/OAuth 환경변수를 기대하므로 plain `./gradlew bootRun`은
  일반적인 local 명령이 아니다.
- Compose가 `schema.sql`을 적용하는 시점은 빈 MySQL volume 최초 기동뿐이다.
- local Redis는 authentication/TLS를 사용하지 않는다.
- AWS client는 credential 없이 생성될 수 있지만 실제 presign/delete에는 유효한 AWS 설정이 필요하다.
- CDN domain이 비어 있으면 생성한 photo URL은 실제 serving URL이 아니다.
- fake AI E2E는 callback server port 8080을 사용하므로 별도 `bootRun`과 동시에 실행하지 않는다.
- 실제 OAuth round trip에는 환경별 provider credential과 redirect URI 설정이 필요하다.

## Known Gaps

local setup을 자동으로 reset/migrate하는 task는 없다. 기존 volume schema 변경은 수동으로 다룬다.

## Update When

Java/Gradle, Compose service/port, Spring profile, local defaults, status/Swagger path가 바뀔 때 갱신한다.

## Validation

```bash
docker compose config --quiet
./gradlew test
```
