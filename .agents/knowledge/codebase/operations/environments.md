# Environments

## Scope

local, integration, dev와 prod의 profile, dependency, feature mode, logging과 automation 차이를 설명한다.

## Read When

환경별 property, mode, environment variable, logging, Redis isolation 또는 deploy automation을 바꿀 때 읽는다.

## Authoritative Sources

- `application.properties`, `application-docker.properties`
- `logback-spring.xml`, `docker-compose.yml`
- `.github/workflows/deploy.yml`
- `terraform/ec2.tf`, `terraform/user_data/was.sh.tftpl`

## Current Matrix

| Environment | Spring profile | DB/Redis | AI | Geo | Swagger | Logging | Redis prefix | Automation |
|---|---|---|---|---|---|---|---|---|
| local | `docker` | Compose | default noop | default noop | on | text | empty | none |
| integration | `docker` | Compose | test override; E2E fake | default noop | on | text | empty | local task |
| dev | default | dev MySQL + shared Redis | workflow fake | workflow Kakao | on | JSON, dev environment | `dev_` | `dev` push |
| prod | default | Terraform has prod MySQL + shared Redis | default noop | default noop | off | JSON intended | empty | no app deploy workflow |

prod 행은 repository에서 확인되는 현재 automation이다. `APP_ENV=prod`는 의도일 뿐 이를 주입하는
production workflow는 아직 없다.

## Configuration Names

값이나 credential은 기록하지 않고 이름과 역할만 다룬다.

- DB/Redis connection and `REDIS_KEY_PREFIX`
- `JWT_SECRET`, Google/Kakao OAuth client names
- `APP_AI_MODE`, `APP_GEO_MODE`, `KAKAO_REST_API_KEY`, `APP_GEO_LOOKUP_CONCURRENCY`
- `SWAGGER_ENABLED`
- `AWS_REGION`, S3/CDN and photo upload limit names
- `APP_ENV`

정확한 property mapping과 default는 `application*.properties`가 권위다.

## Invariants

- secret/credential 값을 knowledge에 복제하지 않는다.
- default, workflow override와 아직 없는 production automation을 구분한다.
- shared Redis의 dev prefix를 application key와 Spring Session namespace 모두에서 보존한다.

## Known Gaps

- production application deploy workflow와 확정된 production runtime injection이 없다.
- local과 deployed environment의 실제 OAuth/S3 동작에는 별도 credential 운영이 필요하다.

## Update When

profile, default/override, dependency topology, environment variable 이름, Swagger/logging/Redis prefix 또는
automation이 바뀔 때 갱신한다.

## Validation

```bash
docker compose config --quiet
rg -n 'APP_ENV|APP_AI_MODE|APP_GEO_MODE|SWAGGER_ENABLED|REDIS_KEY_PREFIX' \
  src/main/resources .github/workflows terraform
```
