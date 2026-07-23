# Deployment

## Scope

현재 dev 애플리케이션 배포, Docker runtime, Terraform recipe와 수동 운영 경계를 설명한다.

## Read When

deploy workflow, preflight, health gate, container, environment injection, Terraform 또는 recovery를 바꿀 때 읽는다.

## Authoritative Sources

- `.github/workflows/deploy.yml`, `.github/workflows/ci.yml`
- `Dockerfile`
- `terraform/README.md`, `terraform/*.tf`, `terraform/user_data/*.tftpl`
- `application.properties`, intro/status API implementation

## Current Dev Deployment

1. `dev` branch push가 모든 path에서 workflow를 시작한다.
2. `deploy-dev` concurrency group으로 배포를 직렬화한다.
3. GitHub OIDC로 AWS deploy role을 assume한다.
4. commit SHA tag Docker image를 ECR에 push한다.
5. SSM으로 dev WAS에 remote script를 보낸다.
6. 기존 container를 내리기 전에 일부 `.env` key를 preflight한다.
7. nginx no-query access log 설정을 idempotent하게 보정한다.
8. 새 image를 pull한다.
9. 기존 `laimory` container를 stop/remove한다.
10. host network와 rotated `json-file` logging으로 새 container를 실행한다.
11. `/api/v1/intro`를 최대 90초 polling한다.
12. 실패하면 새 container log를 출력하고 workflow를 실패시킨다.

workflow는 dev에서 Redis prefix, application environment, AI/geo mode와 Swagger switch를 명시적으로
주입한다. 이름과 의미만 문서화하며 값이나 credential은 workflow/config가 권위다.

### Preflight

현재 기존 container stop 전에 확인하는 key:

- `JWT_SECRET` minimum length
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
- `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`
- `.env`가 `APP_PUSH_MODE=firebase`일 때만: `/home/ubuntu/app/secrets/firebase-service-account.json`
  존재·non-empty 검사 후, pull한 image의 runtime user(appuser, UID 1001)로 `test -r`까지 통과해야
  구 컨테이너를 중지한다(파일은 chown 1001·0400 — root 관점 검사만으론 권한 문제를 못 잡음).
  통과 시 read-only bind mount + `GOOGLE_APPLICATION_CREDENTIALS` 조건부 주입, noop/미설정이면 mount 없이 기동

`DB_*`, `REDIS_*`, `KAKAO_REST_API_KEY`는 현재 preflight하지 않는다.
dev는 Kakao geo mode를 켜므로 API key 누락 시 기존 container 제거 후 새 앱 boot가 실패할 수 있다.
Firebase credential은 파일 mount로만 전달하며 즉시 완화책은 `.env`를 noop으로 되돌린 재배포다
(FID 등록 API/DB는 유지 — 절차·rollback은 `terraform/README.md` FCM runbook).

### Container

- Java 21 multi-stage image, runtime non-root UID 1001
- application port 8080, host network
- management port 9090도 host network에 bind된다. nginx는 Actuator를 proxy하지 않으며, live 접근은
  monitoring source SG가 추가된 뒤에만 허용한다.
- `json-file` rotation: 10 MB × 3
- image build는 `-x test`
- ECR lifecycle은 최근 15개 image를 보존

## Health and Recovery

- deploy gate는 `/api/v1/intro`다. DB 연결과 `app_config` row를 사용한다.
- `/status`는 DB connection probe지만 deploy gate가 아니다.
- 두 endpoint 모두 Redis, Kakao, S3 전체 준비 상태를 검증하지 않는다.
- Prometheus/Grafana 장애는 앱 기동·요청·deploy health gate에 영향을 주지 않는다.
- health failure 시 이전 image로 자동 rollback하지 않는다.

## Terraform and Manual Operations

- Terraform은 AWS Sandbox nuke 후 stack을 재구성하는 recipe이며 local state를 사용한다.
- live environment에 blanket apply하지 않는다. plan 범위를 좁혀 사람이 drift와 영향을 검토한다.
- 기존 WAS/MySQL/ELK는 `user_data` change를 ignore한다. 수정은 새 instance만 자동 재현하며
  기존 instance에는 SSM/manual 적용이 필요하다.
- DNS는 Gabia, TLS는 certbot runbook으로 운영한다.
- Terraform에는 prod topology가 있지만 repository에 production application deploy workflow는 없다.
- state와 secret tfvars는 credential을 포함할 수 있어 commit하지 않는다.

## Invariants

- preflight와 health gate를 기존 container stop보다 앞뒤 어느 위치에서 수행하는지 정확히 유지한다.
- deploy workflow의 실제 variable 이름과 Terraform output 설명을 맞춘다.
- user data를 live mutation mechanism으로 설명하지 않는다.
- Terraform plan/apply에는 운영자 승인과 범위 review가 필요하다.

## Known Gaps

- incomplete preflight, automatic rollback, dependency-complete readiness check와 prod app workflow가 없다.

## Update When

trigger/concurrency, image build, preflight, env injection, container rollout, health/rollback, Terraform lifecycle 또는
manual runbook 경계가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew build
docker build -t laimory:local .
terraform fmt -check -recursive terraform
terraform -chdir=terraform validate
git diff --check
```

`terraform plan`은 AWS profile과 secret 변수가 준비된 운영자 환경에서만 수행하고 결과를 사람이 검토한다.
