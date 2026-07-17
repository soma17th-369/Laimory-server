# Codebase Overview

## Scope

Android 앱이 사용하는 Laimory REST 백엔드의 기술 구성과 기능 경계를 요약한다.

## Read When

저장소를 처음 탐색하거나 변경이 어느 feature와 외부 시스템에 닿는지 판단할 때 읽는다.

## Authoritative Sources

- `build.gradle`, Gradle Wrapper
- `src/main/java/com/laimory/server` package tree
- `src/main/resources/application*.properties`
- `docker-compose.yml`, `Dockerfile`
- `.github/workflows/*.yml`, `terraform/*.tf`

## Current Implementation

- Java 21, Spring Boot 3.5.8, Gradle 8.14.3 Wrapper 기반 단일 Spring Boot 배포물이다.
- Spring MVC, JPA/Hibernate, MySQL 8, Redis 7, Spring Security OAuth2 Client,
  Spring Session Redis와 springdoc-openapi를 사용한다. 외부 API 병렬 호출(지오코딩)에는
  WebClient/Reactor를 쓴다(webflux 의존성 공존, 서블릿 MVC 유지).
- MySQL은 영속 데이터와 AI staging을, Redis는 draft task·callback token 사용 횟수·app code·
  OAuth session을 저장한다. 사진 본문은 S3, 공개 URL은 CloudFront를 사용한다.

주요 feature package:

| Package | Responsibility |
|---|---|
| `timeline` | 비동기 timeline draft, event/item 영속화, 사진 업로드와 cleanup |
| `auth`, `user` | Google/Kakao OIDC, app handoff code, access/refresh token과 사용자 |
| `geo` | Kakao Maps enrich와 noop provider |
| `appconfig` | 공개 intro 설정 |
| `common` | response envelope, error, transaction ID, Redis gateway |
| `config` | Security, OpenAPI, async, scheduling, JPA auditing |

외부 연동은 Google/Kakao OIDC, Kakao Maps, S3, CloudFront, MySQL, Redis다.
AI dispatcher는 현재 `noop`과 dev/test용 `fake`만 존재한다.

dev 배포는 `dev` push에서 GitHub Actions가 ECR image를 만들고 SSM으로 EC2에 배포한다.
Terraform은 AWS 인프라를 재현하는 recipe를 소유한다.

## Invariants

- code/config/schema/workflow가 문서보다 우선한다.
- 환경 설정 문서에는 변수 이름과 역할만 기록하고 값·credential은 복제하지 않는다.
- `/a/api`의 의도된 인증 계약과 현재 enforcement 상태를 구분한다.

## Known Gaps

- production AI adapter는 없다.
- `/a/api`는 bearer 인증 영역으로 문서화돼 있지만 현재 API security chain은 `permitAll`이다.
- request JWT filter와 authenticated userId 전파가 없어 timeline은 임시 `DEFAULT_USER_ID=0`을 쓴다.
- repository에 prod 애플리케이션 자동 배포 workflow는 없다.

## Update When

기술 버전, feature 책임, 외부 시스템, deploy topology 또는 위 known gap이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
./gradlew dependencies
```
