# Environments

## Scope

local, integration, dev와 prod의 profile, dependency, feature mode, logging과 automation 차이를 설명한다.

## Read When

환경별 property, mode, environment variable, logging, Redis isolation, monitoring topology 또는 deploy
automation을 바꿀 때 읽는다.

## Authoritative Sources

- `application.properties`, `application-docker.properties`
- `logback-spring.xml`, `docker-compose.yml`
- `.github/workflows/deploy.yml`
- `terraform/ec2.tf`, `terraform/security_groups.tf`, `terraform/user_data/*.tftpl`
- `deploy/monitoring/*`

## Current Matrix

| Environment | Spring profile | DB/Redis | AI | Geo | Push | Swagger | Logging | Redis prefix | Automation |
|---|---|---|---|---|---|---|---|---|---|
| local | `docker` | Compose | default noop | default noop | default noop | on | text | empty | none |
| integration | `docker` | Compose | default noop; test spy/simulation | default noop | default noop | on | text | empty | local task |
| dev | default | dev MySQL + shared Redis | `.env` 전환(기본 noop) | `.env` Kakao | `.env` 전환(기본 noop) | on | JSON, dev environment | `dev_` | `dev` push |
| prod | default | Terraform has prod MySQL + shared Redis | default noop | default noop | default noop | off | JSON intended | empty | no app deploy workflow |

배포된 환경의 runtime 값은 전부 host `/home/ubuntu/app/.env`가 소유한다(workflow `-e` 주입 없음).
dev deploy pre-flight는 dev 고정값(`REDIS_KEY_PREFIX=dev_`·`APP_ENV=dev`·`APP_GEO_MODE=kakao`·
`SWAGGER_ENABLED=true`)과 `APP_AI_MODE`/`APP_PUSH_MODE`를 exact-one으로 검증하고, 위반이면 기존
container를 내리기 전에 실패한다. firebase 전환 시 ADC 경로(`GOOGLE_APPLICATION_CREDENTIALS`)도
`.env`가 소유하고 pre-flight가 service-account 파일 존재·가독성을 검사한 뒤 read-only mount만
추가한다(절차는 `terraform/README.md` FCM runbook). `APP_COMMIT_SHA`는 배포 workflow가 `.env`에
원자 upsert하는 유일한 key다.

prod 행은 repository에서 확인되는 현재 automation이다. `APP_ENV=prod`는 신규 박스 user_data seed와
recipe의 계약이며 이를 배포하는 production workflow는 아직 없다.

모든 profile에서 app port와 분리된 management port 9090을 사용한다. Actuator의 공통
`environment` metric tag는 `APP_ENV`를 쓰며 미주입 local/integration은 `local`, dev는 `.env`의
`APP_ENV=dev`가 된다. management endpoint의 실제 네트워크 접근 허용은 환경별 SG가 소유한다.

dev monitoring recipe는 별도 private On-Demand t3.medium에 있고 prod는 수집하지 않는다. monitoring
host가 dev WAS management 9090, dev host node 9100, dev MySQL 3306, shared Redis 6379와 dev ELK
9200으로 나가는 source-limited 경로만 갖는다. Grafana는 dev WAS nginx/SSM을 통해서만 접근하며,
monitoring 장애는 application 배포·health gate 의존성이 아니다.

## Configuration Names

값이나 credential은 기록하지 않고 이름과 역할만 다룬다.

- DB/Redis connection and `REDIS_KEY_PREFIX`
- `JWT_SECRET`, Google/Kakao OAuth client names
- `APP_AI_MODE`, `APP_GEO_MODE`, `KAKAO_REST_API_KEY`, `APP_GEO_LOOKUP_CONCURRENCY`
- `APP_PUSH_MODE`, `GOOGLE_APPLICATION_CREDENTIALS`(credential 값이 아니라 컨테이너 내부 JSON 파일 경로)
- `SWAGGER_ENABLED`
- `APP_COMMIT_SHA`(비밀 아님, dev deploy image SHA), `TIMELINE_STUCK_AFTER`
- `AWS_REGION`, S3/CDN and photo upload limit names
- `TIMELINE_PHOTO_DELETE_WORKER_ENABLED`, `TIMELINE_PHOTO_DELETE_FIXED_DELAY`,
  `TIMELINE_PHOTO_DELETE_BATCH_SIZE`
- `APP_ENV`

정확한 property mapping과 default는 `application*.properties`가 권위다.

## Invariants

- secret/credential 값을 knowledge에 복제하지 않는다.
- application default, host `.env` 소유 값과 아직 없는 production automation을 구분한다.
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
