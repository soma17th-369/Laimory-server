# Laimory dev monitoring

Prometheus, Grafana, blackbox exporter와 MySQL/Redis exporter를 private dev monitoring EC2 한 대에서
실행하는 운영 자산이다. 실제 AWS·host 상태가 권위 원천이며, 이 저장소는 전체 인프라의 자동 재구축을
보장하지 않는다.

## 구성과 범위

- Prometheus: 30초 수집, TSDB 7일 또는 12GB 중 먼저 도달한 제한
- Tempo: dev WAS의 OTLP gRPC(4317) trace 수신, 로컬 스토리지 `block_retention: 48h`,
  metrics generator off (#277)
- Grafana: Prometheus metrics, read-only Elasticsearch log datasource(`laimory-*` wildcard)와 Tempo trace datasource
- blackbox exporter: public dev HTTPS `/status`를 60초마다 확인
- node_exporter: monitoring, dev WAS, dev MySQL, Redis, ELK와 prod WAS 2대의 private IP:9100
- textfile collector: monitoring의 CloudWatch/Elasticsearch와 WAS(dev·prod)의 Filebeat self-metric
- central exporter: USAGE-only dev MySQL 계정과 INFO/PING-only Redis ACL 계정
- dashboard: `Laimory / Overview`, `JVM & Spring`, `Infrastructure`, `Logs`
- alert: target/probe/TLS, HTTP 오류·지연, stuck task, JVM/Hikari, host disk/memory/OOM,
  MySQL/Redis, AWS credit 수집, log pipeline, Prometheus self-health
- notification: Grafana native Discord contact point, firing과 resolved 모두 전송

Grafana 3000은 loopback과 monitoring private IP에, Tempo OTLP 4317은 monitoring private IP에만
publish한다(dev WAS의 push 유입 — SG source 계약은 environments.md). Prometheus 9090, Tempo 3200,
blackbox 9115, mysqld exporter 9104, redis exporter 9121은 Docker network에만 expose한다.
`/status`는 DB 중심 health이며 Redis와 외부 연동까지 포괄하는 readiness가 아니다.

resource limit은 Prometheus 1GiB(초기 2GiB에서 #277이 회수 — 실사용 135MiB·active series 14,761
기준 7.5배 여유), Tempo 768MiB, Grafana 768MiB, central exporter 각각 192MiB다. dashboard
refresh는 30초다. 24시간 관찰에서 host memory 75% 초과가 15분 이상 반복되거나 OOM/restart가
생기면 collector와 label을 먼저 줄인다. active series 10,000 초과, root disk 70% 초과, scrape
duration이 interval의 50% 이상인 상태가 계속되면 원인을 줄인 뒤에도 해소되지 않을 때 t3.large를
별도 변경으로 검토한다. monitoring EC2의 CPU credit과 root EBS 지표는 5분마다 CloudWatch에서 읽어
Infrastructure dashboard에 표시한다.

## Tempo trace 수집

Tempo는 monolithic 모드로 `tempo/tempo.yml`을 사용한다. OTLP gRPC 4317 receiver만 열고
(`0.0.0.0:4317` 명시 — 2.7+ 기본 bind가 localhost), 로컬 스토리지 `tempo-data` 볼륨에
`block_retention: 48h`로 보관한다. S3 backend와 metrics generator는 쓰지 않는다.

**tempo 서비스는 compose healthcheck를 정의하지 않는다(규율의 명시적 예외)** — 공식 이미지가
distroless(shell/wget 부재)이고 native `--health` 플래그는 Tempo 3.0+ 전용이라 2.x에는 컨테이너
내부 검사 수단이 없다. 반영·점검 시 아래 대체 확인을 수행한다.

```bash
cd /opt/laimory-monitoring
sudo docker compose ps tempo
sudo docker compose exec grafana wget -q --spider http://tempo:3200/ready && echo "tempo ready"
```

host 반영은 `Existing live rollout`의 upload 절차로 세 자산(`docker-compose.yml`·`tempo/tempo.yml`·
`grafana/provisioning/datasources/tempo.yml`)을 S3에 올린 뒤, monitoring host SSM 세션에서 아래를
실행한다. datasource provisioning은 Grafana 시작 시에만 로드되므로 grafana 재시작까지가 반영이다.

```bash
BACKUP_BUCKET='<backup bucket>'
BASE="s3://$BACKUP_BUCKET/bootstrap/monitoring"
sudo install -d -m 0755 /opt/laimory-monitoring/tempo
sudo aws s3 cp "$BASE/docker-compose.yml" /opt/laimory-monitoring/docker-compose.yml \
  --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "$BASE/tempo/tempo.yml" /opt/laimory-monitoring/tempo/tempo.yml \
  --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "$BASE/grafana/provisioning/datasources/tempo.yml" \
  /opt/laimory-monitoring/grafana/provisioning/datasources/tempo.yml \
  --region ap-northeast-2 --only-show-errors
cd /opt/laimory-monitoring
sudo docker compose config --quiet
sudo docker compose up -d
sudo docker compose restart grafana
```

rollback은 아래 순서로 실행 가능해야 한다. Grafana DB에 등록된 datasource는 provisioning 파일
삭제만으로는 지워지지 않으므로 삭제 전용 임시 provisioning을 반드시 거친다.

```bash
# 0) (운영자 로컬) compose를 이전 버전으로 S3 원래 key에 복원 업로드 — 반영 시 기록한
#    ROLLBACK_PREFIX snapshot이 있으면 그 object를, 최초 등재 롤백이라 snapshot이 없으면
#    git 이전 버전 파일을 사용한다. 신규 2종의 S3 key는 aws s3 rm으로 삭제한다.
# 이하 monitoring host SSM 세션:
cd /opt/laimory-monitoring
sudo aws s3 cp "s3://<backup-bucket>/bootstrap/monitoring/docker-compose.yml" docker-compose.yml \
  --region ap-northeast-2 --only-show-errors
sudo rm -f tempo/tempo.yml
sudo rm -f grafana/provisioning/datasources/tempo.yml
sudo tee grafana/provisioning/datasources/delete-tempo.yml > /dev/null <<'YML'
apiVersion: 1
deleteDatasources:
  - name: Tempo
    orgId: 1
YML
sudo docker compose config --quiet
# plain `up -d`는 정의가 사라진 tempo 컨테이너를 orphan으로 계속 돌린다 — --remove-orphans 필수.
sudo docker compose up -d --remove-orphans
sudo docker compose restart grafana
sudo rm -f grafana/provisioning/datasources/delete-tempo.yml
```

`tempo-data` 볼륨 삭제는 rollback 필수 단계가 아니며 별도 승인된 정리 작업으로만 한다.

## Alert rule source와 release

alert rule은 `grafana/alert-rule-files.txt`가 소유하는 책임별 `*-rules.yml` 8개로 관리한다. 파일 하나에는
동일한 운영 책임의 group만 두며, UID를 바꾸는 migration이 아니라면 기존 UID를 유지한다. 라이브 EC2에서
provisioning YAML을 직접 편집하지 않는다.

```bash
# repository에서 매 변경마다 실행
deploy/monitoring/scripts/validate-alert-rules.sh
deploy/monitoring/tests/test-alert-rule-scripts.sh
.github/scripts/test-monitoring-deploy-contract.sh
```

PR CI는 위 검증을 수행한다. alert manifest, `*-rules.yml`, publish/deploy/validate script 또는
`deploy-monitoring.yml`이 `dev`에 merge되면 `Deploy monitoring alert rules` workflow가 실행된다.
workflow는 commit SHA prefix에 `If-None-Match: *` 조건으로 각 파일을 생성하고 checksum manifest를
마지막에 업로드한 뒤 monitoring EC2에 SSM command를 보낸다. 같은 SHA 재시도에서는 기존 bytes가
동일한 object만 허용하고, 다른 bytes이면 immutable collision으로 실패한다. push 실행은 SSM 전 현재
`dev` HEAD를 다시 확인하므로 뒤늦게 실행된 과거 SHA는 host에 적용되지 않는다. 명시적으로 고른
`workflow_dispatch` release는 이 자동 최신성 검사를 우회한다. 다른 path만 바뀐 merge에는 monitoring
workflow가 실행되지 않는다.

repository 설정과 live IAM은 workflow를 merge하기 전에 아래 계약을 충족해야 한다. 권한 변경은
조회 결과와 영향을 검토하고 별도 승인받은 Console 또는 CLI 작업으로 반영한다.

| 이름 | 종류 | 값 |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | Variable | GitHub OIDC deploy role ARN |
| `MONITORING_INSTANCE_ID` | Secret | dev monitoring EC2 instance ID |
| `MONITORING_BACKUP_BUCKET` | Secret | monitoring bootstrap을 가진 backup bucket 이름 |

instance id와 bucket 이름은 Secret이다. Actions는 workflow의 `env:` 블록을 모든 step 헤더의 로그에
그대로 echo하는데 repository Variable은 마스킹되지 않고 이 저장소는 PUBLIC이라, Variable로 두면 매
실행 로그에 두 값이 평문으로 남는다. Secret은 `***`로 마스킹된다. `AWS_DEPLOY_ROLE_ARN`은 비밀이
아니고 값이 보여야 진단이 되므로 Variable로 남긴다.

- GitHub deploy role: `s3:if-none-match` header가 있는 요청만 허용하는 alert release prefix
  `s3:PutObject`, 같은 bytes 재시도 검증용 `s3:GetObject`, monitoring EC2와
  `AWS-RunShellScript` document의 `ssm:SendCommand`, SSM command read
- monitoring EC2 role: `bootstrap/monitoring/*`의 `s3:GetObject`와 SSM Core

자동 배포는 release에 포함된 deploy/validate 도구를 checksum 검증한 뒤 staged 경로에서 실행한다.
규칙 적용과 Grafana hot reload 후 release의 모든 UID가 provisioning API에 실제 등록된 것을 확인한
뒤에만 `/opt/laimory-monitoring/scripts`의 active 도구를 교체하므로 실패한 도구는 다음 rollback
경로에 남지 않는다. rollback은 Grafana가 읽는 alerting 디렉터리를 `0755`로 유지하고 파일만 복원한다.
deployer는 host의 root-only
`secrets/grafana_admin_password`를 사용한다. credential은 GitHub secret, workflow env, S3 release,
SSM command와 process argument에 전달하지 않는다.

운영자 로컬 publish는 자동 workflow 장애 진단이나 과거 release를 명시적으로 만들 때만 사용한다.
script는 alert 자산이 commit되지 않았으면 거부한다.

```bash
BACKUP_BUCKET='<backup bucket>'
RELEASE_URI=$(
  deploy/monitoring/scripts/publish-alert-rules.sh "$BACKUP_BUCKET" sandbox |
    tail -n 1
)
printf '%s\n' "$RELEASE_URI"
```

자동 workflow를 우회해 이미 게시된 release를 수동 반영하거나 rollback할 때는 monitoring host에서
같은 deployer에 원하는 URI를 넘긴다. 비밀번호 입력은 없으며 host secret을 사용한다.

```bash
RELEASE_URI='s3://<backup-bucket>/bootstrap/monitoring/releases/alert-rules/<commit-sha>'
sudo /opt/laimory-monitoring/scripts/deploy-alert-rules.sh "$RELEASE_URI"
```

배포기는 S3 checksum, manifest, 파일 집합, group/UID 중복을 검사하고 root-only backup을 만든다. 기존
release에서 사라진 UID는 임시 `deleteRules`로 삭제하며, hot reload 실패 시 이전 파일과 새로 추가된
UID를 함께 복구한다. 성공하면 적용 release URI를 `grafana/alert-rule-release`에 기록한다. 이전 release로
돌릴 때는 원하는 과거 `RELEASE_URI`로 같은 명령을 다시 실행한다.

### Rule의 환경 범위

alert rule은 두 부류다. 어느 쪽인지는 **rule이 읽는 시계열이 환경마다 존재하는지**로 갈린다.

- **환경 중립 9개** — `laimory_http_5xx_high`, `laimory_http_p95_high`, `laimory_jvm_heap_high`,
  `laimory_hikari_saturated`, `laimory_target_down`, `laimory_host_memory_low`,
  `laimory_filesystem_low`, `laimory_host_oom_kill`, `laimory_processing_stuck`.
  PromQL에 `environment` 셀렉터를 두지 않고 집계 `by (...)`와 조인 `on (...)`에 `environment`를 넣어
  환경마다 별개 alert instance가 나오게 한다. `environment` 라벨은 **선언하지 않는다** — Grafana가
  조건 쿼리 결과의 라벨을 alert instance 라벨로 넘기므로 그대로 흐르고, 여기에 커스텀 라벨을 두면
  쿼리 라벨을 덮어써 다른 환경의 알림이 오표기된다. 라벨 템플릿을 쓰지 않는 이유는 템플릿이 잘못되면
  파일 단위 provisioning이 실패해 같은 파일의 다른 환경 경보까지 함께 죽기 때문이다.
- **환경 고정 나머지** — 해당 환경에만 있는 자산을 읽는다. dev/monitoring 전용 exporter와 수집기
  (`laimory_elk_memory_low`, `laimory_aws_metric_collection_failed`, `laimory_cpu_credit_low`,
  `laimory_prometheus_*`, `laimory_mysql_connections_high`, `laimory_redis_evictions`,
  `laimory_datastore_backend_down`), 공개된 도메인이 있어야 성립하는 probe 계열
  (`laimory_https_probe_failed`, `laimory_tls_expiry_*`), 그리고 환경 공유 자산인
  Elasticsearch의 수집기·클러스터 상태를 monitoring host로 고정해 읽는
  `laimory_elasticsearch_unhealthy`. 이들은 `environment="dev"` 셀렉터를 유지한다.
  log pipeline 계열(`laimory_log_pipeline_unhealthy`, `laimory_filebeat_output_failures`)과
  wildcard index를 environment terms로 나눠 평가하는 `laimory_application_error_log`는
  환경 중립이다.

새 환경을 붙일 때는 rule을 복제하지 않는다. 그 환경의 시계열이 존재하는지 먼저 확인하고, 없으면
수집기부터 설치한다.

### 자동 배포 밖의 자산

`deploy-monitoring.yml`과 `publish-alert-rules.sh`가 다루는 것은 `*-rules.yml`,
`alert-rule-files.txt`, 배포 script(`deploy/publish/validate-alert-rules.sh`)뿐이다.
그 밖의 모든 자산은 **merge만으로 반영되지 않고** monitoring host SSM 세션에서 직접 반영한다:

- 같은 alerting 디렉터리의 `notification-policy.yml`, `templates.yml`, `contact-points.yml`
  — reload endpoint가 alerting provisioning 디렉터리 전체를 다시 읽는다
- `datasources/*.yml` — reload도 없다. **Grafana 기동 시에만 로드**되므로 재시작까지 필요하다(#370)
- `docker-compose.yml`(monitoring·elk), `dashboards/json/*.json`, `prometheus/prometheus.yml`,
  `scripts/install-secret.sh`·`validate-secrets.sh`

```bash
cd /opt/laimory-monitoring
sudo install -m 0644 -b <새 파일> grafana/provisioning/alerting/<대상 파일>
read -rp 'Grafana admin username [laimory]: ' GRAFANA_ADMIN_USER
GRAFANA_ADMIN_USER=${GRAFANA_ADMIN_USER:-laimory}
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/api/admin/provisioning/alerting/reload
unset GRAFANA_ADMIN_USER
```

`notification-policy.yml`의 `group_by`는 `environment`를 포함한다. 빼면 같은 rule의 dev 알림과
prod 알림이 한 그룹으로 묶여, 이미 활성인 그룹에 얹힌 알림이 `group_interval`만큼 지연된다.

## Secret gate

다음 파일은 Git, S3 bootstrap, command argument에 값을 넣지 않는다. Secret을 소비하는
Grafana, mysqld exporter, redis exporter는 `restart: on-failure`로 process 장애만 Docker가 복구한다.
비밀이 없는 Prometheus와 blackbox는 `unless-stopped`를 유지한다. host boot는 systemd가 전체 stack을
시작하고, Docker service를 재시작했다면 `sudo systemctl start laimory-monitoring`으로 일곱 secret을
다시 확인한다.

| 파일 | 소비 UID:GID | 내용 |
|---|---:|---|
| `grafana_admin_password` | `472:0` | 최초 Grafana `laimory` admin password |
| `grafana_secret_key` | `472:0` | datasource/contact credential 암호화 key |
| `elasticsearch_api_key` | `472:0` | Elasticsearch create API key 응답의 `encoded` 값 |
| `discord_webhook_url` | `472:0` | 지정 Discord channel incoming webhook URL |
| `google_oauth_client_secret` | `472:0` | Grafana Google OAuth client secret (client ID는 `.env`의 `GRAFANA_GOOGLE_CLIENT_ID`) |
| `mysql_exporter_my.cnf` | `65534:0` | exporter 전용 `[client]` credential |
| `redis_exporter_password.json` | `59000:59000` | exporter URI별 password JSON |

parent directory는 `0700 root:root`, 각 파일은 `0400`이다. systemd는
`scripts/validate-secrets.sh`로 일곱 파일의 non-empty/owner/mode를 모두 확인하므로 일부만 준비된
stack은 시작하지 않는다. 비밀이 없는 Prometheus와 blackbox만 먼저 기동할 수 있다.

SSM Session Manager로 monitoring host에 접속한 뒤 stdin 전용 helper로 주입한다.

```bash
cd /opt/laimory-monitoring

read -rsp 'Grafana admin password: ' SECRET_VALUE; echo
printf %s "$SECRET_VALUE" | sudo scripts/install-secret.sh grafana_admin_password
unset SECRET_VALUE

openssl rand -hex 32 | sudo scripts/install-secret.sh grafana_secret_key

read -rsp 'Discord webhook URL: ' SECRET_VALUE; echo
printf %s "$SECRET_VALUE" | sudo scripts/install-secret.sh discord_webhook_url
unset SECRET_VALUE

read -rsp 'Google OAuth client secret: ' SECRET_VALUE; echo
printf %s "$SECRET_VALUE" | sudo scripts/install-secret.sh google_oauth_client_secret
unset SECRET_VALUE
```

Grafana admin username 기본값은 `laimory`다. admin password는 최초 DB 생성 때 각인된다. 이후 파일만
바꾸지 말고 Grafana admin password reset 절차를 사용한다. `grafana_secret_key`는 재부팅과 재배포에도
유지해야 기존 암호화 값을 읽는다.

Google OAuth 사용자를 추가할 때는 Grafana에 이메일로 선등록한 뒤 **첫 로그인 전에**
`GF_AUTH_OAUTH_ALLOW_INSECURE_EMAIL_LOOKUP=true`를 임시로 켠다. Grafana 기본값은 OAuth 로그인을
이메일로 매칭하지 않으므로, auth 링크가 없는 선등록 계정은 `allow_sign_up=false`에 걸려
"Sign up is disabled"로 거부된다(2026-08-26 실측). 첫 로그인으로 링크가 생기면
(`authLabels: ["Google"]`) 플래그를 제거하고 재시작한다 — 이후 로그인은 링크로 매칭된다.

## Exporter identity와 secret

### node_exporter

`v1.12.1` linux-amd64 archive의 고정 SHA256을 검증하고 별도 system user로 실행한다. IMDSv2에서
private IPv4를 얻어 그 주소의 9100에만 bind한다. textfile collector directory는
`/var/lib/node_exporter/textfile_collector` 하나로 고정하고 root oneshot collector가 atomic rename한
비밀 없는 `.prom` 파일만 읽는다.

살아 있는 monitoring, dev WAS, dev MySQL, Redis, ELK와 prod WAS 2대 각 host의 SSM 세션에서 같은
명령을 실행한다. 기존 Redis host처럼 AWS CLI가 아직 없으면 먼저 아래처럼 설치한다.

```bash
if ! command -v aws >/dev/null; then
  sudo apt-get update -y
  sudo apt-get install -y curl unzip
  curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip \
    -o /tmp/awscliv2.zip
  unzip -q /tmp/awscliv2.zip -d /tmp
  sudo /tmp/aws/install
fi

BACKUP_BUCKET='<backup bucket>'
sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/node-exporter/install.sh" \
  /usr/local/sbin/install-laimory-node-exporter \
  --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /usr/local/sbin/install-laimory-node-exporter
sudo /usr/local/sbin/install-laimory-node-exporter \
  v1.12.1 b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63
sudo systemctl is-active node_exporter
```

### MySQL

dev MySQL host의 SSM 세션에서 helper를 exact S3 key로 받고 실행한다. password는 hidden prompt로만
받는다. 계정 host는 확인된 monitoring private IP로 제한되고 `MAX_USER_CONNECTIONS 3`, `USAGE`만
남는다. exporter는 global status/variables collector만 켠다.

```bash
BACKUP_BUCKET='<backup bucket>'
sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/scripts/configure-mysql-exporter-user.sh" \
  /usr/local/sbin/configure-laimory-mysql-exporter-user \
  --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /usr/local/sbin/configure-laimory-mysql-exporter-user
sudo /usr/local/sbin/configure-laimory-mysql-exporter-user \
  10.0.32.14 laimory_exporter
```

동일한 exporter password를 monitoring host의 hidden prompt에 넣는다.

```bash
read -rsp 'MySQL exporter password: ' SECRET_VALUE; echo
printf '[client]\nuser=laimory_exporter\npassword="%s"\n' "$SECRET_VALUE" |
  sudo /opt/laimory-monitoring/scripts/install-secret.sh mysql_exporter_my.cnf
unset SECRET_VALUE
```

`#` 뒤의 문자열도 password로 보존되는지는 실제 secret이 아닌 fixture와 MySQL 8 option-file parser로
회귀 검증한다. `--show`는 synthetic 값에만 사용하며, 마지막 명령이 전체 `#tail`을 포함한 한 줄을
출력해야 한다.

```bash
MYSQL_OPTION_TEST_PASSWORD='abcdefghijklmnop#tail'
MYSQL_OPTION_TEST_FILE="$(mktemp)"
trap 'rm -f "$MYSQL_OPTION_TEST_FILE"' EXIT
printf '[client]\nuser=laimory_exporter\npassword="%s"\n' \
  "$MYSQL_OPTION_TEST_PASSWORD" > "$MYSQL_OPTION_TEST_FILE"
docker run --rm --entrypoint my_print_defaults \
  -v "$MYSQL_OPTION_TEST_FILE:/tmp/mysql-exporter-my.cnf:ro" \
  mysql:8.0 \
  --defaults-file=/tmp/mysql-exporter-my.cnf --show client |
  grep -Fx -- "--password=$MYSQL_OPTION_TEST_PASSWORD"
rm -f "$MYSQL_OPTION_TEST_FILE"
trap - EXIT
unset MYSQL_OPTION_TEST_PASSWORD MYSQL_OPTION_TEST_FILE
```

`SHOW GLOBAL STATUS`와 `SHOW GLOBAL VARIABLES`는 성공해야 한다. application table `SELECT`,
`INSERT`, `CREATE`, `ALTER`는 모두 실패해야 한다.

### Redis

Redis host의 SSM 세션에서 helper를 실행한다. 기존 app ACL credential과 새 exporter credential을
각각 hidden prompt로 받는다. exporter는 `INFO`, `PING`, `CLIENT SETNAME`만 허용하고 key pattern,
channel, GET/SCAN/EVAL/SLOWLOG, write/admin 명령은 허용하지 않는다.

```bash
BACKUP_BUCKET='<backup bucket>'
sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/scripts/configure-redis-exporter-user.sh" \
  /usr/local/sbin/configure-laimory-redis-exporter-user \
  --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /usr/local/sbin/configure-laimory-redis-exporter-user
sudo /usr/local/sbin/configure-laimory-redis-exporter-user \
  laimory_app laimory_monitoring
```

동일한 exporter password를 monitoring host에서 URI-keyed JSON으로 만든다. password는 URI나
container environment/argument에 넣지 않는다.

```bash
read -rsp 'Redis exporter password: ' SECRET_VALUE; echo
jq -cn --arg password "$SECRET_VALUE" \
  '{"redis://laimory_monitoring@10.0.32.11:6379":$password}' |
  sudo /opt/laimory-monitoring/scripts/install-secret.sh redis_exporter_password.json
unset SECRET_VALUE
```

## Elasticsearch read-only API key

monitoring host에서 Elasticsearch superuser password를 curl의 interactive prompt로만 입력한다.
응답의 `encoded` 값은 stdout에 표시하지 않고 바로 Grafana-readable secret 파일에 저장한다.

```bash
curl -fsS -u elastic \
  -X POST http://10.0.32.13:9200/_security/api_key \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"grafana-laimory-logs",
    "role_descriptors":{
      "grafana_logs_reader":{
        "cluster":["monitor"],
        "indices":[{
          "names":["laimory-*"],
          "privileges":["read","view_index_metadata"]
        }]
      }
    }
  }' |
  jq -er .encoded |
  sudo /opt/laimory-monitoring/scripts/install-secret.sh elasticsearch_api_key
```

아래 read-only privilege 검사는 API key 원문을 argv에 넣지 않는다. 결과에서 `read`와
`view_index_metadata`만 true, `write`와 `delete`는 false여야 한다.

```bash
API_KEY=$(sudo cat /opt/laimory-monitoring/secrets/elasticsearch_api_key | tr -d '\r\n')
printf 'header = "Authorization: ApiKey %s"\n' "$API_KEY" |
  curl -fsS --config - \
    -X POST http://10.0.32.13:9200/_security/user/_has_privileges \
    -H 'Content-Type: application/json' \
    -d '{
      "index":[{
        "names":["laimory-*"],
        "privileges":["read","view_index_metadata","write","delete"]
      }]
    }' |
  jq .
unset API_KEY
```

## Log pipeline wildcard rollout

datasource(`grafana/provisioning/datasources/elasticsearch.yml`)와 Logs dashboard는 alert rule
자동 배포 대상이 아니다. 순서가 어긋나면 수집기-부재 분기가 오발화하거나(1을 건너뛰고 rule을
먼저 배포), prod ERROR 경보가 dev index만 읽어 동작하지 않는다(4를 생략).

1. **prod WAS 2대에 Filebeat 수집기 설치** — 위 collector 설치 절차 그대로. rule 배포 전에 끝낸다.
2. **`dev` merge** — alert rule은 자동 배포된다.
3. **(운영자 로컬)** `Existing live rollout`의 upload 절차로 두 자산을 S3에 올린다:
   `grafana/provisioning/datasources/elasticsearch.yml` ·
   `grafana/provisioning/dashboards/json/laimory-logs.json`
4. **monitoring host** — 기존 파일을 backup한 뒤 교체하고, 위 절차로 API key를 `laimory-*` 범위로
   재발급해 secret을 교체한 다음 Grafana를 재시작한다(datasource provisioning은 시작 시에만 로드).

```bash
cd /opt/laimory-monitoring
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
sudo install -d -m 0700 "rollback/log-wildcard-$STAMP"
sudo cp grafana/provisioning/datasources/elasticsearch.yml "rollback/log-wildcard-$STAMP/"
sudo cp grafana/provisioning/dashboards/json/laimory-logs.json "rollback/log-wildcard-$STAMP/"
BACKUP_BUCKET='<backup bucket>'
BASE="s3://$BACKUP_BUCKET/bootstrap/monitoring"
sudo aws s3 cp "$BASE/grafana/provisioning/datasources/elasticsearch.yml" \
  grafana/provisioning/datasources/elasticsearch.yml --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "$BASE/grafana/provisioning/dashboards/json/laimory-logs.json" \
  grafana/provisioning/dashboards/json/laimory-logs.json --region ap-northeast-2 --only-show-errors
# 이 시점에 elasticsearch_api_key를 laimory-* 범위로 재발급해 교체한다 (위 절차)
sudo docker compose restart grafana
```

rollback은 backup 파일 2개를 제자리에 복원하고 이전 범위의 key로 secret을 되돌린 뒤 grafana를
재시작한다. datasource는 uid(`elasticsearch-dev`)가 같아 삭제 전용 provisioning 없이 파일 교체만으로
되돌아간다.

## Existing live rollout

이 절차는 이미 만들어진 monitoring/WAS를 바꾸는 수동 SSM 경로다. 자동 provisioning은 하지 않는다.
먼저 운영자 로컬에서 비밀 없는 변경 자산을 exact S3 key로 올린다.

```bash
set -euo pipefail
BACKUP_BUCKET='<backup bucket>'
ROLLBACK_PREFIX="bootstrap/rollback/monitoring/$(date -u +%Y%m%dT%H%M%SZ)"
while IFS= read -r asset; do
  if aws s3api head-object --bucket "$BACKUP_BUCKET" \
    --key "bootstrap/monitoring/$asset" --profile sandbox >/dev/null 2>&1; then
    aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/$asset" \
      "s3://$BACKUP_BUCKET/$ROLLBACK_PREFIX/$asset" \
      --profile sandbox --region ap-northeast-2 --only-show-errors
  fi
  aws s3 cp "deploy/monitoring/$asset" \
    "s3://$BACKUP_BUCKET/bootstrap/monitoring/$asset" \
    --profile sandbox --region ap-northeast-2 --only-show-errors
done <<'ASSETS'
docker-compose.yml
tempo/tempo.yml
grafana/provisioning/datasources/elasticsearch.yml
grafana/provisioning/datasources/tempo.yml
node-exporter/install.sh
grafana/provisioning/dashboards/json/laimory-overview.json
grafana/provisioning/dashboards/json/laimory-jvm-spring.json
grafana/provisioning/dashboards/json/laimory-infrastructure.json
grafana/provisioning/dashboards/json/laimory-logs.json
scripts/backup-ebs-snapshot.sh
scripts/backup-mysql-dump.sh
scripts/collect-aws-metrics.sh
scripts/collect-elasticsearch-metrics.sh
scripts/collect-filebeat-metrics.sh
scripts/configure-mysql-backup-user.sh
systemd/laimory-aws-metrics.service
systemd/laimory-aws-metrics.timer
systemd/laimory-ebs-snapshot.service
systemd/laimory-ebs-snapshot.timer
systemd/laimory-elasticsearch-metrics.service
systemd/laimory-elasticsearch-metrics.timer
systemd/laimory-filebeat-metrics.service
systemd/laimory-filebeat-metrics.timer
systemd/laimory-mysqldump-backup.service
systemd/laimory-mysqldump-backup.timer
ASSETS
if aws s3api head-object --bucket "$BACKUP_BUCKET" \
  --key bootstrap/elk/filebeat.yml --profile sandbox >/dev/null 2>&1; then
  aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/elk/filebeat.yml" \
    "s3://$BACKUP_BUCKET/$ROLLBACK_PREFIX/elk/filebeat.yml" \
    --profile sandbox --region ap-northeast-2 --only-show-errors
fi
aws s3 cp deploy/elk/filebeat.yml \
  "s3://$BACKUP_BUCKET/bootstrap/elk/filebeat.yml" \
  --profile sandbox --region ap-northeast-2 --only-show-errors
```

backup bucket은 versioning을 전제로 하지 않으므로 기존 object snapshot이 성공한 뒤에만 원래 key를
덮어쓴다. rollback 시에는 기록한 `ROLLBACK_PREFIX`의 object를 원래 key로 복원한다.

monitoring role에는 아래 read-only inline policy를 Console 또는 동등한 검토된 CLI 변경으로 추가한다.
resource write 권한과 CloudWatch wildcard action은 추가하지 않는다.

```bash
aws iam put-role-policy --profile sandbox \
  --role-name laimory-monitoring-role \
  --policy-name laimory-monitoring-cloudwatch-read \
  --policy-document '{
    "Version":"2012-10-17",
    "Statement":[
      {"Effect":"Allow","Action":"cloudwatch:GetMetricData","Resource":"*"},
      {"Effect":"Allow","Action":"ec2:DescribeInstances","Resource":"*"}
    ]
  }'
```

monitoring host의 SSM 세션에서 정확한 파일만 교체하고 timer를 켠다.

```bash
BACKUP_BUCKET='<backup bucket>'
BASE="s3://$BACKUP_BUCKET/bootstrap/monitoring"
ROLLBACK_DIR=/opt/laimory-monitoring/rollback/pre-operational-observability
sudo install -d -m 0700 "$ROLLBACK_DIR" "$ROLLBACK_DIR/dashboards"
if [ ! -f "$ROLLBACK_DIR/.complete" ]; then
  if find "$ROLLBACK_DIR" -mindepth 1 -type f -print -quit | grep -q .; then
    echo "incomplete monitoring rollback packet already exists" >&2
    exit 1
  fi
  for dashboard in laimory-overview laimory-jvm-spring laimory-infrastructure laimory-logs; do
    sudo install -m 0600 \
      "/opt/laimory-monitoring/grafana/provisioning/dashboards/json/$dashboard.json" \
      "$ROLLBACK_DIR/dashboards/$dashboard.json"
  done
  sudo install -m 0600 /etc/systemd/system/node_exporter.service \
    "$ROLLBACK_DIR/node_exporter.service"
  sudo touch "$ROLLBACK_DIR/.complete"
fi
while IFS='|' read -r source destination; do
  sudo aws s3 cp "$BASE/$source" "$destination" \
    --region ap-northeast-2 --only-show-errors
done <<'ASSETS'
node-exporter/install.sh|/opt/laimory-monitoring/node-exporter/install.sh
grafana/provisioning/dashboards/json/laimory-overview.json|/opt/laimory-monitoring/grafana/provisioning/dashboards/json/laimory-overview.json
grafana/provisioning/dashboards/json/laimory-jvm-spring.json|/opt/laimory-monitoring/grafana/provisioning/dashboards/json/laimory-jvm-spring.json
grafana/provisioning/dashboards/json/laimory-infrastructure.json|/opt/laimory-monitoring/grafana/provisioning/dashboards/json/laimory-infrastructure.json
grafana/provisioning/dashboards/json/laimory-logs.json|/opt/laimory-monitoring/grafana/provisioning/dashboards/json/laimory-logs.json
scripts/collect-aws-metrics.sh|/opt/laimory-monitoring/scripts/collect-aws-metrics.sh
scripts/collect-elasticsearch-metrics.sh|/opt/laimory-monitoring/scripts/collect-elasticsearch-metrics.sh
systemd/laimory-aws-metrics.service|/etc/systemd/system/laimory-aws-metrics.service
systemd/laimory-aws-metrics.timer|/etc/systemd/system/laimory-aws-metrics.timer
systemd/laimory-elasticsearch-metrics.service|/etc/systemd/system/laimory-elasticsearch-metrics.service
systemd/laimory-elasticsearch-metrics.timer|/etc/systemd/system/laimory-elasticsearch-metrics.timer
ASSETS
sudo chmod 0750 /opt/laimory-monitoring/node-exporter/install.sh \
  /opt/laimory-monitoring/scripts/collect-aws-metrics.sh \
  /opt/laimory-monitoring/scripts/collect-elasticsearch-metrics.sh
sudo /opt/laimory-monitoring/node-exporter/install.sh \
  v1.12.1 b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63
sudo systemctl daemon-reload
sudo systemctl enable --now \
  laimory-aws-metrics.timer laimory-elasticsearch-metrics.timer
sudo systemctl start \
  laimory-aws-metrics.service laimory-elasticsearch-metrics.service
```

dev WAS의 SSM 세션에서는 기존 Filebeat credential을 다시 표시하거나 재입력하지 않는다. bind mount
원본만 교체한 뒤 기존 container를 restart한다.

```bash
BACKUP_BUCKET='<backup bucket>'
ROLLBACK_DIR=/var/lib/laimory-monitoring/rollback/pre-operational-observability
sudo install -d -m 0700 "$ROLLBACK_DIR"
if [ ! -f "$ROLLBACK_DIR/.complete" ]; then
  if find "$ROLLBACK_DIR" -mindepth 1 -type f -print -quit | grep -q .; then
    echo "incomplete WAS rollback packet already exists" >&2
    exit 1
  fi
  sudo install -m 0600 /home/ubuntu/filebeat.yml "$ROLLBACK_DIR/filebeat.yml"
  sudo install -m 0600 /etc/systemd/system/node_exporter.service \
    "$ROLLBACK_DIR/node_exporter.service"
  sudo touch "$ROLLBACK_DIR/.complete"
fi
sudo apt-get update -y
sudo apt-get install -y jq
sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/node-exporter/install.sh" \
  /usr/local/sbin/install-laimory-node-exporter \
  --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /usr/local/sbin/install-laimory-node-exporter
sudo /usr/local/sbin/install-laimory-node-exporter \
  v1.12.1 b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63

sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/elk/filebeat.yml" \
  /tmp/laimory-filebeat.yml --region ap-northeast-2 --only-show-errors
# 실행 중 container의 file bind mount가 같은 inode의 새 내용을 보도록 destination을 in-place overwrite한다.
sudo cp /tmp/laimory-filebeat.yml /home/ubuntu/filebeat.yml
sudo rm -f /tmp/laimory-filebeat.yml
sudo chown ubuntu:ubuntu /home/ubuntu/filebeat.yml
sudo chmod 0644 /home/ubuntu/filebeat.yml
if ! sudo docker exec filebeat filebeat test config -e --strict.perms=false; then
  sudo cp "$ROLLBACK_DIR/filebeat.yml" /home/ubuntu/filebeat.yml
  sudo chown ubuntu:ubuntu /home/ubuntu/filebeat.yml
  sudo chmod 0644 /home/ubuntu/filebeat.yml
  exit 1
fi
sudo docker restart filebeat

sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/scripts/collect-filebeat-metrics.sh" \
  /usr/local/sbin/collect-laimory-filebeat-metrics \
  --region ap-northeast-2 --only-show-errors
sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/systemd/laimory-filebeat-metrics.service" \
  /etc/systemd/system/laimory-filebeat-metrics.service \
  --region ap-northeast-2 --only-show-errors
sudo aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/monitoring/systemd/laimory-filebeat-metrics.timer" \
  /etc/systemd/system/laimory-filebeat-metrics.timer \
  --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /usr/local/sbin/collect-laimory-filebeat-metrics
sudo chmod 0644 /etc/systemd/system/laimory-filebeat-metrics.*
sudo systemctl daemon-reload
sudo systemctl enable --now laimory-filebeat-metrics.timer
sudo systemctl start laimory-filebeat-metrics.service
```

세 collector의 `up`/freshness metric이 정상인 것을 확인한 뒤, WAS SSM 작업과 겹치지 않게 앱
변경을 정상 deploy workflow로 배포하고 health gate를 통과시킨다. 실제 배포 SHA의 build info와
callback/FCM/PROCESSING metric을 확인한 뒤에만 monitoring host에서 Grafana를 재시작해 새 dashboard와
alert를 provisioning한다. PROCESSING task는 각자 저장 시점의 TTL(현재 계약 3분)로 소멸하므로 배포 직후
index는 곧 새 task만 반영한다. 이 순서는 배포 도중 collector/metric 부재 alert가 Discord로
잘못 발송되는 것을 피한다.

```bash
cd /opt/laimory-monitoring
sudo docker compose restart grafana
```

마지막으로 두 host에서 아래 `Extended metric collectors` 검증과 dashboard/alert/Discord 확인을 수행한다.

## Start, status, reload

```bash
cd /opt/laimory-monitoring
sudo scripts/validate-secrets.sh
sudo systemctl start laimory-monitoring
sudo systemctl status laimory-monitoring --no-pager
sudo docker compose config --quiet
sudo docker compose ps
```

Prometheus config를 바꾼 경우 먼저 promtool로 검사한 뒤 reload한다.

```bash
sudo docker run --rm --entrypoint promtool \
  -v /opt/laimory-monitoring/prometheus:/etc/prometheus:ro \
  prom/prometheus:v3.13.1 check config /etc/prometheus/prometheus.yml
sudo systemctl reload laimory-monitoring
```

운영 host에서 interpolation된 credential이 출력될 수 있는 `docker compose config`는 쓰지 않고
`config --quiet`만 사용한다. 별도 데이터 삭제 승인이 없으면 `docker compose down -v`를 실행하지 않는다.

## Extended metric collectors

monitoring host의 `laimory-aws-metrics.timer`는 5분마다 IMDSv2로 자기 instance/root volume을 확인하고
`cloudwatch:GetMetricData`와 `ec2:DescribeInstances` 최소권한으로 CPU credit, surplus charge, root EBS
queue/busy/read-write latency를 수집한다. 여덟 query가 모두 `Complete`일 때만 collector를 up으로
기록하고 partial/error 응답은 실패로 노출한다. `laimory-elasticsearch-metrics.timer`는 1분마다 기존
read-only API key로 cluster health와 가장 최근 dev log 시각을 읽는다. API key가 아직 비어 있으면
service의 `ExecCondition`이 수집을 건너뛴다.

WAS의 Filebeat HTTP stats는 `127.0.0.1:5066`에만 열고,
`laimory-filebeat-metrics.timer`가 output result/queue/active/harvester 지표로 변환한다. SG나 public
port는 추가하지 않는다. 최근 log 시각은 무트래픽과 장애를 구분할 수 없으므로 표시만 하고 freshness
alert 조건으로 쓰지 않는다.

```bash
sudo systemctl list-timers 'laimory-*-metrics.timer'
sudo systemctl start laimory-aws-metrics.service
sudo systemctl start laimory-elasticsearch-metrics.service
sudo systemctl status laimory-aws-metrics.service \
  laimory-elasticsearch-metrics.service --no-pager
curl -fsS "http://$(hostname -I | awk '{print $1}'):9100/metrics" |
  grep -E '^laimory_(aws|elasticsearch)_'
```

WAS에서는 아래처럼 확인한다.

```bash
curl -fsS http://127.0.0.1:5066/stats | jq '.libbeat.output.events,.libbeat.pipeline.queue,.filebeat'
sudo systemctl start laimory-filebeat-metrics.service
curl -fsS "http://$(hostname -I | awk '{print $1}'):9100/metrics" |
  grep '^laimory_filebeat_'
```

## prod MySQL backup

prod MySQL은 관리형이 아니라 백업이 저절로 생기지 않는다. AWS Backup·DLM은 조직 SCP로 거부되어
(2026-08-24 policy simulation 실측: `backup-storage:*` 명시 거부) EBS 스냅샷도 직접 호출로 만든다.
백업은 두 갈래이고 각 갈래의 마지막 성공이 26시간을 넘으면 `backup-rules.yml`의 rule이 발화한다.

- **논리 덤프** — prod MySQL host의 `laimory-mysqldump-backup.timer`(매일 04:15 KST)가
  `mysqldump --single-transaction`을 gzip해 backup bucket `prod-mysql/mysqldump/`(30일 만료
  lifecycle)로 올린다. **일관된 복구의 권위는 이쪽이다.**
- **EBS 스냅샷** — monitoring host의 `laimory-ebs-snapshot.timer`(매일 04:30 KST)가 root volume
  snapshot 생성 → 완료 대기 → 14일 초과분 prune → 최신 완료 시각을 textfile metric으로 기록한다.
  스냅샷은 crash-consistent(전원 차단과 동일)이며 복원 기동은 InnoDB crash recovery 경로다.

두 host 모두 node_exporter textfile collector가 전제다(위 node_exporter 설치 절차 참고).
IAM은 로컬 운영자 권한으로 반영한다 — prod MySQL role의 `laimory-prod-mysqldump-s3-put`
(dump prefix 한정 `s3:PutObject`)과 monitoring role의 `laimory-prod-mysql-ebs-snapshot`
(생성은 대상 volume 한정, 삭제는 `laimory-backup=prod-mysql` tag 조건).

설정 파일은 각 host가 소유하며 저장소에 두지 않는다(버킷 이름에 계정 ID 포함).

```bash
# prod MySQL host: /etc/laimory/mysqldump-backup.env (root 0600)
MYSQL_USER='laimory_backup'
MYSQL_PASSWORD='<backup password>'
S3_PREFIX='s3://<backup bucket>/prod-mysql/mysqldump'

# monitoring host: /etc/laimory/ebs-snapshot-backup.env (root 0600)
VOLUME_ID='<prod MySQL root EBS volume id>'
RETENTION_DAYS=14
```

prod MySQL host 설치 — 백업 계정을 만들고(대화형: backup password 입력. MySQL은 host 네이티브
설치이고 root는 socket 인증이라 root password는 필요 없다) script/unit을 설치한다.
timer enable 직후 service를 1회 실행해 첫 metric을 만든다 — 이게 없으면 absent 절이 즉시 발화한다.

```bash
BACKUP_BUCKET='<backup bucket>'
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/scripts/configure-mysql-backup-user.sh" \
  /tmp/configure-mysql-backup-user.sh --region ap-northeast-2 --only-show-errors
sudo bash /tmp/configure-mysql-backup-user.sh
sudo rm -f /tmp/configure-mysql-backup-user.sh

sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/scripts/backup-mysql-dump.sh" \
  /usr/local/sbin/backup-laimory-mysql-dump --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/systemd/laimory-mysqldump-backup.service" \
  /etc/systemd/system/laimory-mysqldump-backup.service --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/systemd/laimory-mysqldump-backup.timer" \
  /etc/systemd/system/laimory-mysqldump-backup.timer --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /usr/local/sbin/backup-laimory-mysql-dump
sudo chmod 0644 /etc/systemd/system/laimory-mysqldump-backup.*
sudo install -d -m 0700 /etc/laimory
sudo vi /etc/laimory/mysqldump-backup.env   # 위 포맷, 저장 후 chmod 0600
sudo chmod 0600 /etc/laimory/mysqldump-backup.env
sudo systemctl daemon-reload
sudo systemctl enable --now laimory-mysqldump-backup.timer
sudo systemctl start laimory-mysqldump-backup.service
```

monitoring host 설치 — 같은 순서로 설치하고 1회 실행한다.

```bash
BACKUP_BUCKET='<backup bucket>'
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/scripts/backup-ebs-snapshot.sh" \
  /opt/laimory-monitoring/scripts/backup-ebs-snapshot.sh --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/systemd/laimory-ebs-snapshot.service" \
  /etc/systemd/system/laimory-ebs-snapshot.service --region ap-northeast-2 --only-show-errors
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/systemd/laimory-ebs-snapshot.timer" \
  /etc/systemd/system/laimory-ebs-snapshot.timer --region ap-northeast-2 --only-show-errors
sudo chmod 0750 /opt/laimory-monitoring/scripts/backup-ebs-snapshot.sh
sudo chmod 0644 /etc/systemd/system/laimory-ebs-snapshot.*
sudo install -d -m 0700 /etc/laimory
sudo vi /etc/laimory/ebs-snapshot-backup.env   # 위 포맷, 저장 후 chmod 0600
sudo chmod 0600 /etc/laimory/ebs-snapshot-backup.env
sudo systemctl daemon-reload
sudo systemctl enable --now laimory-ebs-snapshot.timer
sudo systemctl start laimory-ebs-snapshot.service
```

확인은 설정이 아니라 산출물로 한다 — `laimory_mysqldump_up`·`laimory_ebs_snapshot_up`이 1이고
`*_last_success_unixtime_seconds`가 갱신되는지, S3 dump object와 EC2 snapshot 실물이 생기는지,
service를 의도적으로 1회 실패시켜(예: env 파일 임시 이동) 두 alert가 발화하는지 본다.

정리(uninstall)는 각 host에서 timer disable → unit/script/`.prom`/설정 파일 제거, 로컬 운영자
권한에서 위 inline policy 2건과 bucket lifecycle rule(`expire-prod-mysqldump-30d`) 제거다.

## Discord smoke test

실제 application/infra 장애를 만들지 않고 synthetic expression으로 firing과 resolved를 확인한다.
메시지는 alert/status/severity/environment/job/instance, 짧은 요약과 runbook만 포함하며 raw log,
request/response body, transactionId, user/task/FID, 좌표, exception message를 포함하지 않는다.

```bash
cd /opt/laimory-monitoring
read -rp 'Grafana admin username [laimory]: ' GRAFANA_ADMIN_USER
GRAFANA_ADMIN_USER=${GRAFANA_ADMIN_USER:-laimory}

sudo install -m 0644 grafana/smoke/smoke-rule.firing.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/api/admin/provisioning/alerting/reload
# Discord firing 도착 확인

sudo install -m 0644 grafana/smoke/smoke-rule.resolved.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/api/admin/provisioning/alerting/reload
# Discord resolved 도착 확인

sudo install -m 0644 grafana/smoke/smoke-rule.delete.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/api/admin/provisioning/alerting/reload
sudo rm /opt/laimory-monitoring/grafana/provisioning/alerting/smoke-rule.yml
unset GRAFANA_ADMIN_USER
```

파일을 바로 지우면 provisioned rule이 Grafana DB에 남으므로 반드시 `deleteRules`를 먼저 reload한다.

## Alert runbook

### Target down

Grafana의 Prometheus target 화면에서 `job`과 `instance`를 확인한다. exporter container/systemd,
source-limited SG와 대상 private port를 순서대로 확인한다. app의 9090은 monitoring SG에서만 접근해야
한다. monitoring 장애가 application API 장애로 전파되면 구현 결함이다.

### HTTPS probe failed

DNS, TLS 만료, ALB(리스너 규칙·타깃 헬스), `/status` 응답을 분리해 확인한다. `/status`가 성공해도
Redis, Kakao, S3까지 ready라는 뜻은 아니다.

### TLS certificate expiry

dev·prod 모두 prod ALB의 ACM 인증서(`*.laimory.app` 포함)로 종단하며 ACM이 자동 갱신한다(#369).
만료 30일 전 warning과 14일 전 critical은 겹치지 않는다. 경보가 뜨면 ACM 콘솔에서 해당 인증서의
갱신 상태와 실패 사유를 확인한다 — 주 원인은 DNS 검증 CNAME 레코드 소실이며, Route53에서 복원하면
ACM이 재시도한다. 갱신 후 blackbox의 새 만료 시각을 확인한다.

### HTTP errors or latency

Overview에서 최소 traffic 조건과 status/URI를 확인한 뒤 Logs dashboard에서 같은 시간대를 좁힌다.
원문·body 심층 분석은 Kibana로 이동한다. alert/Discord에는 원문을 복사하지 않는다.

`Application ERROR log detected`는 Elasticsearch에 최근 5분 동안 `service=laimory`,
`environment=dev`, `level=ERROR` 문서가 하나라도 있으면 pending 없이 warning으로 발화한다.
단일 사용자 요청 실패를 서비스 전체 장애와 동일시하지 않으므로 critical은 기존 target/probe/backend
down, OOM, 5xx 비율 조건이 소유한다. 알림의 `runbook` 링크는 인증된 Kibana Discover를 최근 15분
ERROR 필터와 `message`, `level`, `errorCode`, `path`, `exceptionType` 열로 바로 연다. Discord에는
원문, body, transactionId, 사용자·task·FID·좌표·예외 원문을 넣지 않는다.

WARN은 기본적으로 사람을 호출하지 않는다. Logs dashboard의 `ERROR & WARN Logs`에서 증가한
ERROR/WARN 데이터 포인트를 클릭하면 선택한 시각 전후 5분, 같은 environment와 level로 필터된 Kibana
Discover가 새 탭에서 열린다. 링크는 Grafana의 클릭 시각과 series 이름만 전달하며 원문 로그를 URL에
넣지 않는다. 직접 검색할 때는 아래 KQL로 해당 문서를 조사한다.

```text
service:"laimory" and environment:"dev" and level:"WARN"
```

### JVM or Hikari pressure

JVM & Spring에서 heap/GC, pending connection, acquire timeout을 함께 본다. 단발 GC나 traffic 없는
비율은 alert하지 않는다. DB connection pressure와 같이 발생하면 DB부터 확인한다.

### Host resource pressure

Infrastructure에서 실제 filesystem과 `MemAvailable`을 확인한다. tmpfs/overlay 같은 pseudo filesystem은
disk alert에서 제외된다. 일반 host는 `MemAvailable < 15%`, filesystem cache를 적극 사용하는 ELK는
`MemAvailable < 10%`가 각각 10분 지속될 때 경고한다. ELK 경고는 Elasticsearch heap, filesystem cache,
swap activity와 OOM 이력을 함께 확인하고, 실제 reclaim 실패나 OOM 징후 없이 cache만 큰 경우에는 스펙
증설 근거로 단독 사용하지 않는다. collector/cardinality를 줄여도 monitoring medium의 memory 75%가
지속되면 별도 resize 변경을 검토한다.

### Timeline PROCESSING stuck

Overview의 stuck count와 Timeline workflow/callback, Redis target 상태를 함께 본다. 이 값은 90초를 넘고
3분 TTL 만료 전인 PROCESSING task만 센다. gauge 조회가 만료 구간(`score <= now-3m`) member를 index에서
먼저 제거하고, task JSON 저장과 보조 sorted-set index 갱신은 원자적이며 terminal 전이 때 index에서
제거된다. 경보 창이 90초(90s~3m)로 짧아 stuck rule은 전용 30초 group(`laimory-lifecycle-stuck`)에서
pending 없이(`for: 0s`) 즉시 발화한다 — resolve도 그만큼 빠르므로 지나간 발화는 Grafana alert
history로 확인한다.

draft POST가 dispatch 실패 502(-1009)를 반환한 경우에도 접수 여부 불명(UNKNOWN)이면 기존 PROCESSING
task가 남아 stuck에 잡힐 수 있다 — 3분 안에 AI callback이 오면 terminal로 빠지고, callback 없이 만료되면
FAILED 전이 없이 사라져 이후 폴링·콜백은 404(-1001)다. Redis 장애 시 gauge는 NaN이 되고 별도 Redis
target alert가 원인을 알린다. index는 관측 전용이므로 task 상태를 수동 수정해 alert를 끄지 않는다.

### Timeline PHOTO delete worker

모든 process의 worker는 매일 03:00 KST에 250개 단위 `SKIP LOCKED` claim으로 서로 다른 job을 처리한다.
checked-in 기본은 process당 concurrency 1, 최대 4 batch/60초이므로 process 하나가 약 1,000건, 계획된
두 process가 약 2,000건까지 한 run에서 claim할 수 있다. 정상 job도 다음 실행까지 최대 약 24시간 대기할
수 있고 S3 실패·crash·process 전체 run budget 초과분은 다음 날 실행으로 이월된다. 처리 기회는 KST
생성일 기준 D+1~D+3 세 번의 일일 실행뿐이다 — 창을 벗어난 미완료 job은 더 이월되지 않고 보존되며,
worker가 run 시작에 `expiredCount`만 담은 ERROR 로그를 남겨 기존 application ERROR 경보가 발화한다
(job ID·object key 미포함). dev WAS에서는 전체
환경을 출력하지 말고 각 container의
`TIMELINE_PHOTO_DELETE_WORKER_ENABLED`, `TIMELINE_PHOTO_DELETE_CONCURRENCY`,
`TIMELINE_PHOTO_DELETE_MAX_BATCHES_PER_RUN` 값만 확인한다.

worker는 checked-in default로 활성화된다. flag를 바꿀 때는 host `.env`를 수정한 뒤 deploy workflow를
다시 실행하거나 기존 container를 stop/remove하고 동일한 `docker run --env-file` 인자로 새로 만들어야
한다. `docker restart`는 생성 당시 환경을 재사용하므로 변경된 `.env`를 읽지 않는다.

flag가 true인데 job이 줄지 않으면 직전 03:00 KST에 두 app process가 가용했는지 확인하고 application
log의 `PHOTO 삭제 worker run 시작`, `PHOTO 삭제 batch 완료`, `PHOTO 삭제 worker run 완료`를 조회한다.
`claimed`, `relinkedCancelled`, `requested`, `s3Succeeded`, `s3Failed`, `unreported`, `dbCompleted`,
`deferred`, 단계별 오류 수와 `durationMs`를 process-wide run budget, MySQL/Hikari 상태, S3/IAM 오류와
함께 확인한다. 실패
job과 그 FK가 가리키는 원문 PHOTO Item은 처리 창 안에서 재시도되는 복구 권위이므로 둘 중 하나를 수동
삭제하거나 object key를 로그에 복사하지 않는다. 처리 창이 끝난 만료 job과 Item도 원인 확인을 위해
보존한다 — 만료 ERROR 경보를 받으면 수동 삭제 대신 원인을 조사한다. monitoring 자산 변경은 앱 자동 배포에 포함되지 않으므로 기존
provisioning 파일을 백업한 뒤 자산을 반영하고 Grafana provisioning reload/restart 절차를 따른다.

### AWS metric collection

`systemctl status laimory-aws-metrics.service`와 journal을 확인하고 IMDSv2, AWS CLI,
monitoring role의 `cloudwatch:GetMetricData`/`ec2:DescribeInstances`를 순서대로 본다. CPU credit이 낮으면
Infrastructure의 node CPU/load와 surplus charge를 함께 보고 일시 burst인지 지속 포화인지 구분한다.
권한을 write로 넓히지 않는다.

### Log pipeline

Logs dashboard에서 Filebeat up/queue/output failure와 Elasticsearch up/red/unassigned shard를 먼저 본다.
WAS의 `docker logs --tail 100 filebeat`, ELK의 cluster health, monitoring collector timer 순으로 좁힌다.
최근 indexed log 시각만 오래된 경우에는 먼저 실제 앱 traffic이 있었는지 확인한다. 복구를 위해 API key나
Filebeat role에 write 범위를 추가하지 않는다.

### MySQL or Redis pressure

`mysql_up`/`redis_up`과 exporter `up`을 구분한다. MySQL connection ratio와 Redis eviction increase를
확인한다. exporter scrape가 성공해도 backend 연결·인증이 실패하면 별도 critical alert가 발생한다.
exporter 권한을 넓혀 해결하지 말고 필요한 collector와 최소 권한 계약부터 확인한다.

### Prometheus self health

last reload, rule evaluation, TSDB compaction, WAL truncation과 monitoring disk를 확인한다. 실패한 config는
promtool 통과 전 reload하지 않는다. volume은 보존한 채 service만 stop/restart한다.

## Failure boundaries

- monitoring stack이 내려가면 Grafana metric dashboard와 Discord alert가 중단되지만, 별도 host의 앱 API와
  Filebeat→Elasticsearch→Kibana 로그 경로는 계속 동작한다. monitoring 장애가 API 응답 실패로 전파되면
  구성 결함이다.
- ELK가 내려가면 Grafana Logs dashboard/Explore와 Kibana 상세 검색만 실패한다. Prometheus dashboard,
  metric alert와 앱 API는 계속 동작한다. ELK 복구 전 로그 backfill은 각 앱 container의 제한된 rotated
  local log 범위만 보장된다.

## Rollback

alert rule은 원하는 과거 immutable release URI를 `deploy-alert-rules.sh`에 다시 넘겨 rollback한다.
배포기가 현재 release에만 있는 UID를 `deleteRules`로 제거하므로 provisioning 파일을 직접 지우지 않는다.
방금 실패한 배포는 자동 복구되며, 성공한 배포의 직전 release URI는 출력된 backup directory의
`alert-rule-release`에서 확인한다.

```bash
cd /opt/laimory-monitoring
PREVIOUS_RELEASE_URI=$(
  sudo cat /opt/laimory-monitoring/rollback/alert-rules/<backup-timestamp>/alert-rule-release
)
sudo scripts/deploy-alert-rules.sh "$PREVIOUS_RELEASE_URI"
```

아래는 alert release가 아니라 operational collector/dashboard 보강 전체를 되돌릴 때만 사용한다.
Prometheus의 기존 `node` job과 5대 target은 제거하지 않는다.

```bash
cd /opt/laimory-monitoring
ROLLBACK_DIR=/opt/laimory-monitoring/rollback/pre-operational-observability
for dashboard in laimory-overview laimory-jvm-spring laimory-infrastructure laimory-logs; do
  sudo install -m 0644 "$ROLLBACK_DIR/dashboards/$dashboard.json" \
    "grafana/provisioning/dashboards/json/$dashboard.json"
done
sudo docker compose restart grafana

# monitoring host — collector 정리와 기존 node_exporter unit 복원
sudo systemctl disable --now \
  laimory-aws-metrics.timer laimory-elasticsearch-metrics.timer
sudo systemctl stop \
  laimory-aws-metrics.service laimory-elasticsearch-metrics.service
sudo rm -f /etc/systemd/system/laimory-{aws,elasticsearch}-metrics.{service,timer}
sudo rm -f /opt/laimory-monitoring/scripts/collect-{aws,elasticsearch}-metrics.sh
sudo rm -f /var/lib/node_exporter/textfile_collector/laimory_{aws,elasticsearch}.prom
sudo install -m 0644 "$ROLLBACK_DIR/node_exporter.service" \
  /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl restart node_exporter
sudo rmdir /var/lib/node_exporter/textfile_collector 2>/dev/null || true

# dev WAS — collector/Filebeat 정리와 기존 node_exporter unit 복원
ROLLBACK_DIR=/var/lib/laimory-monitoring/rollback/pre-operational-observability
sudo systemctl disable --now laimory-filebeat-metrics.timer
sudo systemctl stop laimory-filebeat-metrics.service
sudo rm -f /etc/systemd/system/laimory-filebeat-metrics.{service,timer}
sudo rm -f /usr/local/sbin/collect-laimory-filebeat-metrics
sudo rm -f /var/lib/node_exporter/textfile_collector/laimory_filebeat.prom
sudo cp "$ROLLBACK_DIR/filebeat.yml" /home/ubuntu/filebeat.yml
sudo chown ubuntu:ubuntu /home/ubuntu/filebeat.yml
sudo chmod 0644 /home/ubuntu/filebeat.yml
sudo docker restart filebeat
sudo install -m 0644 "$ROLLBACK_DIR/node_exporter.service" \
  /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl restart node_exporter
sudo rmdir /var/lib/node_exporter/textfile_collector 2>/dev/null || true
```

로컬 운영자 권한에서는 이번에 새로 만든
`laimory-monitoring-cloudwatch-read` inline policy를 삭제한다. 배포 전 동명 policy가 있었다면 삭제
대신 snapshot한 기존 document를 복원한다.

```bash
aws iam delete-role-policy --profile sandbox \
  --role-name laimory-monitoring-role \
  --policy-name laimory-monitoring-cloudwatch-read
```

이 rollback은 Prometheus/Grafana volume과 앱/ELK 데이터를 건드리지 않으며 `docker compose down -v`를
실행하지 않는다. 앱 변경 rollback은 별도 image rollback으로 수행한다. 전체 #185 stack 자체를
철거할 때만 각 host의 node_exporter와 MySQL/Redis monitoring identity를 함께 disable/revoke한다.
stack rollback은
`sudo systemctl stop laimory-monitoring`으로 수행하며 TSDB/Grafana volume은 보존한다. 다시 올릴 때는
`sudo systemctl start laimory-monitoring`으로 secret validator를 통과시킨다. 외부 노출을 끊을 때는
prod ALB의 `grafana.laimory.app` host 규칙을 삭제한다(#368) — Kibana host 규칙과 독립이라 Kibana는
보존된다.
