# Deployment

## Scope

현재 dev 애플리케이션 배포, Docker runtime, Terraform recipe와 수동 운영 경계를 설명한다.

## Read When

deploy workflow, preflight, health gate, container, environment injection, Terraform 또는 recovery를 바꿀 때 읽는다.

## Authoritative Sources

- `.github/workflows/deploy.yml`, `.github/workflows/deploy-monitoring.yml`, `.github/workflows/ci.yml`
- `Dockerfile`
- `terraform/README.md`, `terraform/*.tf`, `terraform/user_data/*.tftpl`
- `deploy/monitoring/*`
- `application.properties`, intro/status API implementation

## Current Dev Deployment

1. `dev` branch push 중 Docker image 입력(`src/main`, Gradle build/wrapper, Dockerfile/dockerignore)이나
   `deploy.yml` 자체가 바뀐 경우에만 workflow를 시작한다. test·문서·Terraform·monitoring-only 변경은
   application을 재배포하지 않는다.
2. `deploy-dev` concurrency group으로 배포를 직렬화한다.
3. GitHub OIDC로 AWS deploy role을 assume한다.
4. commit SHA tag Docker image를 ECR에 push한다.
5. SSM으로 dev WAS에 remote script를 보낸다. script는 첫 실패 가능 명령보다 앞에서 EXIT cleanup
   trap을 설치한다.
6. 기존 container를 내리기 전에 `.env` 계약을 preflight한다(secret presence + dev 고정값·mode
   exact-one, 값 비출력 — 아래 Preflight).
7. nginx no-query access log 설정을 idempotent하게 보정한다. `nginx -t` 실패는 배포를 중단한다.
8. ECR login 후 새 image를 pull하고, firebase 모드면 runtime UID 1001 가독성까지 검사한다.
9. 모든 pre-stop 검사·pull이 성공한 뒤에만 `APP_COMMIT_SHA`를 같은 디렉터리 temp+rename으로 `.env`에
   원자 upsert한다(첫 stop 직전 commit point — 이전 실패는 기존 `.env` bytes·SHA를 보존한다).
10. 기존 `laimory` container를 stop/remove한다.
11. `-e`/`--env` 없이 `--env-file /home/ubuntu/app/.env`만으로 새 container를 실행한다(host network,
    rotated `json-file` logging; firebase면 read-only credential mount만 추가).
12. `/api/v1/intro`를 최대 90초 polling한다. 실패하면 새 container log를 출력하고 workflow를 실패시킨다.
13. 성공·실패 어느 종료 경로에서도 EXIT cleanup이 `docker image prune -af`를 정확히 1회 실행한다 —
    어떤 container도 참조하지 않는 tagged/dangling image가 제거되고, prune 실패는 고정 경고만 남기며
    원래 배포 status를 바꾸지 않는다.

## Monitoring Alert Rule Deployment

`deploy-monitoring.yml`은 `dev` push 중 alert manifest, `*-rules.yml`, alert release 도구 또는 workflow
자체가 바뀐 경우에만 별도로 실행된다. GitHub OIDC deploy role로 commit SHA별 S3 release를 게시하고,
monitoring EC2에 SSM command를 보낸다. release object는 `If-None-Match: *` 조건부 생성만 허용하고
같은 SHA 재시도는 기존 bytes가 같을 때만 성공한다. SSM 직전 현재 `dev` HEAD를 확인해 오래된 push
실행이 최신 규칙을 덮어쓰지 못하게 하되 명시적인 `workflow_dispatch` 선택은 허용한다. release의
deploy/validate 도구는 checksum 확인 후 staged 경로에서 실행하며, 규칙 적용과 reload가 성공한 뒤에만
active 경로에 설치한다. host deployer는 manifest/file/UID를 검증하고 root-only backup 후 Grafana
provisioning API를 hot reload한 다음 release의 모든 UID가 실제 조회되는지 확인한다. rollback은
Grafana-readable alerting 디렉터리 mode를 유지한 채 파일만 복구한다. reload 또는 UID 확인이 실패하면
기존 파일과 UID 상태를 복구하고 SSM command와 workflow를 실패 처리한다.

Grafana admin password는 monitoring host의 `0400` secret file만이 소유한다. workflow, S3 release,
SSM command와 process argument에는 credential을 전달하지 않는다. application deploy와 monitoring
deploy는 별도 concurrency group을 사용하며, alert와 무관한 merge는 monitoring deploy를 시작하지 않는다.
live 자동화의 선행 조건은 repository의 monitoring instance/bucket Variable과 deploy role의 scoped S3
conditional PutObject·동일 bytes 검증용 GetObject·monitoring SSM 권한을 Console/검토된 CLI로
반영하는 것이다. Terraform은 재구축 recipe만 소유하며 live apply mechanism이 아니다.

장기 실행 container의 runtime env는 host `.env`가 단일 권위(SSOT)다. workflow는 `-e` override를
사용하지 않고 dev 고정값(Redis prefix·application environment·geo mode·Swagger)과 AI/push mode를
exact-one으로 검증만 하며, `APP_COMMIT_SHA`가 workflow가 `.env`에 쓰는 유일한 key다. 이름과 의미만
문서화하며 값이나 credential은 host `.env`가 권위다.

host `.env`는 container 생성 시 `docker run --env-file`로 읽힌다. 값을 바꾼 뒤 `docker restart`만 하면
기존 container environment가 유지되므로 새 값이 반영되지 않는다. runtime flag 활성화·롤백은 deploy
workflow 재실행 또는 기존 container stop/remove 뒤 동일 인자의 재생성이 필요하다.

### Preflight

현재 기존 container stop 전에 확인하는 계약(실패 진단은 key 이름·개수까지만 — 값 비출력):

- `JWT_SECRET` minimum length, `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`,
  `KAKAO_CLIENT_ID`/`KAKAO_CLIENT_SECRET` presence
- dev 고정값 exact-one: `REDIS_KEY_PREFIX=dev_` · `APP_ENV=dev` · `APP_GEO_MODE=kakao` ·
  `SWAGGER_ENABLED=true` 각각 정확히 한 줄
- `APP_AI_MODE` exact-one(`noop|fake|http`); `http`면 non-empty `APP_AI_HTTP_BASE_URL`도 정확히 한 줄
- `APP_PUSH_MODE` exact-one(`noop|firebase`). `firebase`일 때만:
  `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json` exact-one과
  `/home/ubuntu/app/secrets/firebase-service-account.json` 존재·non-empty 검사 후, pull한 image의
  runtime user(appuser, UID 1001)로 `test -r`까지 통과해야 구 컨테이너를 중지한다(파일은 chown
  1001·0400 — root 관점 검사만으론 권한 문제를 못 잡음). 통과 시 read-only bind mount만 추가하며
  ADC 경로는 `.env`가 소유한다. `noop`이면 mount·credential 검사 없이 기동

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
- 현재 image SHA는 모든 meter의 공통 tag가 아니라 `laimory.build.info` 한 meter에만 노출한다.
- image build는 `-x test`
- ECR lifecycle은 최근 15개 image를 보존

## Health and Recovery

- deploy gate는 `/api/v1/intro`다. DB 연결과 `app_config` row를 사용한다.
- `/status`는 DB connection probe지만 deploy gate가 아니다.
- 두 endpoint 모두 Redis, Kakao, S3 전체 준비 상태를 검증하지 않는다.
- Prometheus/Grafana 장애는 앱 기동·요청·deploy health gate에 영향을 주지 않는다.
- health failure 시 이전 image로 자동 rollback하지 않는다. 이전 image의 host-local cache는 cleanup이
  prune하므로 없을 수 있다 — rollback은 ECR lifecycle(최근 15개 보존)에서 이전 SHA를 다시 pull해
  재배포한다.

PHOTO delete-job schema rollout은 live MySQL에 additive table을 먼저 적용하고 worker 기본 off Server를
배포한 뒤 enqueue와 pending/oldest gauge를 확인한다. 그 다음 host `.env`의 worker flag를 켜고 deploy
workflow를 재실행해 container를 재생성한다. rollback은 같은 방식으로 flag를 끄며 pending job row를
수동 삭제하지 않는다. job은 보존 중인 원문 PHOTO Item을 FK로 참조하므로 backlog를 수동 정리할 때도
job만 또는 Item만 단독 삭제하지 않는다.

## Terraform and Manual Operations

- Terraform은 AWS Sandbox nuke 후 stack을 재구성하는 recipe이며 local state를 사용한다.
- live environment에 blanket apply하지 않는다. plan 범위를 좁혀 사람이 drift와 영향을 검토한다.
- 기존 WAS/MySQL/ELK는 `user_data` change를 ignore한다. 수정은 새 instance만 자동 재현하며
  기존 instance에는 SSM/manual 적용이 필요하다.
- dev monitoring recipe는 private On-Demand t3.medium, encrypted gp3 30GiB, 전용 최소권한
  SSM/bootstrap/CloudWatch read profile을 사용한다. live host와 SG attachment는 Console/SSM으로 반영하고,
  수동 생성 리소스가 Terraform state에 없다는 중복 생성 위험을 runbook에서 관리한다.
- monitoring bootstrap S3 prefix에는 비밀 없는 Compose/config/dashboard/alert/script/systemd 자산만
  둔다. Grafana admin/encryption key, Elasticsearch/Discord와 MySQL/Redis exporter credential은 host의
  UID별 보호 파일에 SSM으로 주입한다. 여섯 파일 중 하나라도 없거나 mode가 다르면 systemd가 fail-closed한다.
- 신규 dev WAS/MySQL/Redis/ELK와 monitoring user data는 같은 pinned node_exporter installer를 exact
  S3 object로 받아 private interface:9100에만 bind하고 textfile directory를 켠다. monitoring의 AWS/ES,
  dev WAS의 Filebeat oneshot timer도 rebuild recipe에 포함한다. 기존 live host는 user data가 아니라
  SSM으로 같은 script/unit을 적용하며 prod에는 설치하지 않는다.
- 공용 EC2 role의 backup write는 `binlog/*`에만 한정해 실행형 monitoring bootstrap object를 기존
  WAS/DB/ELK가 덮어쓸 수 없게 한다.
- live `/grafana/` 개방은 전용 관리 script로 별도 nginx include만 추가·제거한다. script는 기존
  Kibana snippet을 backup하고 `nginx -t`와 reload 실패 시 원복한다.
- 신규 WAS nginx recipe는 application upstream에 `Laimory-Client-IP $remote_addr`를 overwrite한다.
  기존 live dev/prod WAS는 `terraform/README.md`의 SSM runbook으로 backup → 같은 디렉터리 atomic
  교체 → `nginx -t` → reload를 먼저 수행해야 하며, source recipe 변경만으로 live 반영됐다고 보지 않는다.
  application은 loopback nginx header만 신뢰하고 AI의 8080 direct socket은 그대로 기록한다.
- DNS는 Gabia, TLS는 certbot runbook으로 운영한다.
- Terraform에는 prod topology가 있지만 repository에 production application deploy workflow는 없다.
- state와 secret tfvars는 credential을 포함할 수 있어 commit하지 않는다.

## Invariants

- preflight와 health gate를 기존 container stop보다 앞뒤 어느 위치에서 수행하는지 정확히 유지한다.
  `APP_COMMIT_SHA` 원자 upsert는 모든 pre-stop 검사·pull 성공 뒤, 첫 stop 직전에만 수행한다.
- 장기 실행 `docker run`에 `-e`/`--env`를 추가하지 않는다 — runtime env는 host `.env`가 SSOT다.
  일회성 preflight `docker run --rm`은 이 제한 대상이 아니다.
- EXIT cleanup(`docker image prune -af`)은 종료 경로마다 정확히 1회 실행하고 원래 배포 status를
  바꾸지 않는다.
- remote script의 heredoc 본문은 `.github/scripts/test-deploy-contract.sh`가 추출·실행해 검증한다 —
  script 계약을 바꾸면 harness를 같은 변경에서 통과시킨다.
- deploy workflow의 실제 variable 이름과 Terraform output 설명을 맞춘다.
- application deploy trigger는 Docker image와 remote deploy 계약에 영향을 주는 path로만 제한한다.
- monitoring alert workflow는 관련 path로만 trigger하고 credential을 host 밖으로 전달하지 않는다.
- user data를 live mutation mechanism으로 설명하지 않는다.
- Terraform plan/apply에는 운영자 승인과 범위 review가 필요하다.

## Known Gaps

- application의 incomplete preflight, automatic rollback, dependency-complete readiness check와 prod app
  workflow가 없다.

## Update When

trigger/concurrency, image build, preflight, env injection, container rollout, health/rollback, Terraform lifecycle 또는
manual runbook 경계가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew build
docker build -t laimory:local .
python3 -m unittest discover -s terraform/tests -p 'test_*.py'
terraform fmt -check -recursive terraform
terraform -chdir=terraform validate
git diff --check
```

`terraform plan`은 AWS profile과 secret 변수가 준비된 운영자 환경에서만 수행하고 결과를 사람이 검토한다.
