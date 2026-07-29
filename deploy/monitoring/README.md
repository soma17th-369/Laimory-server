# Laimory dev monitoring

Prometheus, Grafana, blackbox exporter와 MySQL/Redis exporter를 private dev monitoring EC2 한 대에서
실행하는 재구축 자산이다. 실제 AWS 반영은 `terraform/README.md`의 Console/SSM runbook을 따른다.
Terraform은 recipe이며 살아 있는 dev에 blanket/target `terraform apply`를 하지 않는다.

## 구성과 범위

- Prometheus: 30초 수집, TSDB 7일 또는 12GB 중 먼저 도달한 제한
- Grafana: Prometheus metrics와 read-only Elasticsearch dev log datasource
- blackbox exporter: public dev HTTPS `/status`를 60초마다 확인
- node_exporter: monitoring, dev WAS, dev MySQL, Redis, ELK의 private IP:9100
- textfile collector: monitoring의 CloudWatch/Elasticsearch와 dev WAS의 Filebeat self-metric
- central exporter: USAGE-only dev MySQL 계정과 INFO/PING-only Redis ACL 계정
- dashboard: `Laimory / Overview`, `JVM & Spring`, `Infrastructure`, `Logs`
- alert: target/probe/TLS, HTTP 오류·지연, stuck task, PHOTO delete backlog, JVM/Hikari, host disk/memory/OOM,
  MySQL/Redis, AWS credit 수집, log pipeline, Prometheus self-health
- notification: Grafana native Discord contact point, firing과 resolved 모두 전송

Grafana 3000만 loopback과 monitoring private IP에 publish한다. Prometheus 9090, blackbox 9115,
mysqld exporter 9104, redis exporter 9121은 Docker network에만 expose한다. `/status`는 DB 중심
health이며 Redis와 외부 연동까지 포괄하는 readiness가 아니다.

초기 resource limit은 Prometheus 2GiB, Grafana 768MiB, central exporter 각각 192MiB다. dashboard
refresh는 30초다. 24시간 관찰에서 host memory 75% 초과가 15분 이상 반복되거나 OOM/restart가
생기면 collector와 label을 먼저 줄인다. active series 10,000 초과, root disk 70% 초과, scrape
duration이 interval의 50% 이상인 상태가 계속되면 원인을 줄인 뒤에도 해소되지 않을 때 t3.large를
별도 변경으로 검토한다. monitoring EC2의 CPU credit과 root EBS 지표는 5분마다 CloudWatch에서 읽어
Infrastructure dashboard에 표시한다.

## Alert rule source와 release

alert rule은 `grafana/alert-rule-files.txt`가 소유하는 책임별 `*-rules.yml` 8개로 관리한다. 파일 하나에는
동일한 운영 책임의 group만 두며, UID를 바꾸는 migration이 아니라면 기존 UID를 유지한다. 라이브 EC2에서
provisioning YAML을 직접 편집하지 않는다.

```bash
# repository에서 매 변경마다 실행
deploy/monitoring/scripts/validate-alert-rules.sh
deploy/monitoring/tests/test-alert-rule-scripts.sh
```

검증과 review가 끝난 commit에서 운영자 로컬이 immutable S3 release를 발행한다. script는 alert 자산이
commit되지 않았으면 거부하고, 각 파일을 commit SHA prefix에 올린 뒤 checksum manifest를 마지막에
업로드한다.

```bash
BACKUP_BUCKET='<backup bucket>'
RELEASE_URI=$(
  deploy/monitoring/scripts/publish-alert-rules.sh "$BACKUP_BUCKET" sandbox |
    tail -n 1
)
printf '%s\n' "$RELEASE_URI"
```

기존 live monitoring host에는 배포 도구를 한 번 설치하고, 이후 도구 자체가 바뀐 release에서만 다시
설치한다. root 실행 파일도 같은 commit SHA release에서 받고 checksum을 먼저 확인한다.

```bash
# monitoring host의 SSM session
RELEASE_URI='s3://<backup-bucket>/bootstrap/monitoring/releases/alert-rules/<commit-sha>'
TOOL_STAGE=$(mktemp -d)
sudo aws s3 cp "$RELEASE_URI/tools/SHA256SUMS" "$TOOL_STAGE/SHA256SUMS" \
  --region ap-northeast-2 --only-show-errors
for tool in deploy-alert-rules.sh validate-alert-rules.sh; do
  sudo aws s3 cp \
    "$RELEASE_URI/tools/$tool" "$TOOL_STAGE/$tool" \
    --region ap-northeast-2 --only-show-errors
done
(cd "$TOOL_STAGE" && sudo sha256sum --check --strict SHA256SUMS)
sudo install -m 0750 "$TOOL_STAGE/deploy-alert-rules.sh" \
  "$TOOL_STAGE/validate-alert-rules.sh" /opt/laimory-monitoring/scripts/
rm -rf "$TOOL_STAGE"
```

release 반영은 monitoring host에서 한 명령과 Grafana admin password 입력으로 끝난다.

```bash
RELEASE_URI='s3://<backup-bucket>/bootstrap/monitoring/releases/alert-rules/<commit-sha>'
sudo /opt/laimory-monitoring/scripts/deploy-alert-rules.sh "$RELEASE_URI"
```

배포기는 S3 checksum, manifest, 파일 집합, group/UID 중복을 검사하고 root-only backup을 만든다. 기존
release에서 사라진 UID는 임시 `deleteRules`로 삭제하며, hot reload 실패 시 이전 파일과 새로 추가된
UID를 함께 복구한다. 성공하면 적용 release URI를 `grafana/alert-rule-release`에 기록한다. 이전 release로
돌릴 때는 원하는 과거 `RELEASE_URI`로 같은 명령을 다시 실행한다.

## Secret gate

다음 파일은 Git, Terraform, S3 bootstrap, command argument에 값을 넣지 않는다. Secret을 소비하는
Grafana, mysqld exporter, redis exporter는 `restart: on-failure`로 process 장애만 Docker가 복구한다.
비밀이 없는 Prometheus와 blackbox는 `unless-stopped`를 유지한다. host boot는 systemd가 전체 stack을
시작하고, Docker service를 재시작했다면 `sudo systemctl start laimory-monitoring`으로 여섯 secret을
다시 확인한다.

| 파일 | 소비 UID:GID | 내용 |
|---|---:|---|
| `grafana_admin_password` | `472:0` | 최초 Grafana admin password |
| `grafana_secret_key` | `472:0` | datasource/contact credential 암호화 key |
| `elasticsearch_api_key` | `472:0` | Elasticsearch create API key 응답의 `encoded` 값 |
| `discord_webhook_url` | `472:0` | 지정 Discord channel incoming webhook URL |
| `mysql_exporter_my.cnf` | `65534:0` | exporter 전용 `[client]` credential |
| `redis_exporter_password.json` | `59000:59000` | exporter URI별 password JSON |

parent directory는 `0700 root:root`, 각 파일은 `0400`이다. systemd는
`scripts/validate-secrets.sh`로 여섯 파일의 non-empty/owner/mode를 모두 확인하므로 일부만 준비된
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
```

Grafana admin password는 최초 DB 생성 때 각인된다. 이후 파일만 바꾸지 말고 Grafana admin password
reset 절차를 사용한다. `grafana_secret_key`는 재부팅과 재배포에도 유지해야 기존 암호화 값을 읽는다.

## Exporter identity와 secret

### node_exporter

`v1.12.1` linux-amd64 archive의 고정 SHA256을 검증하고 별도 system user로 실행한다. IMDSv2에서
private IPv4를 얻어 그 주소의 9100에만 bind한다. textfile collector directory는
`/var/lib/node_exporter/textfile_collector` 하나로 고정하고 root oneshot collector가 atomic rename한
비밀 없는 `.prom` 파일만 읽는다.

살아 있는 monitoring, dev WAS, dev MySQL, Redis, ELK 각 host의 SSM 세션에서 같은 명령을 실행한다.
prod host에는 설치하지 않는다. 기존 Redis host처럼 AWS CLI가 아직 없으면 먼저 아래처럼 설치한다.

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
    "name":"grafana-laimory-dev-logs",
    "role_descriptors":{
      "grafana_logs_reader":{
        "cluster":["monitor"],
        "indices":[{
          "names":["laimory-dev-*"],
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
        "names":["laimory-dev-*"],
        "privileges":["read","view_index_metadata","write","delete"]
      }]
    }' |
  jq .
unset API_KEY
```

## Existing live rollout

이 절차는 이미 만들어진 monitoring/WAS를 바꾸는 수동 SSM 경로다. Terraform apply는 하지 않는다.
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
node-exporter/install.sh
grafana/provisioning/dashboards/json/laimory-overview.json
grafana/provisioning/dashboards/json/laimory-jvm-spring.json
grafana/provisioning/dashboards/json/laimory-infrastructure.json
grafana/provisioning/dashboards/json/laimory-logs.json
scripts/collect-aws-metrics.sh
scripts/collect-elasticsearch-metrics.sh
scripts/collect-filebeat-metrics.sh
systemd/laimory-aws-metrics.service
systemd/laimory-aws-metrics.timer
systemd/laimory-elasticsearch-metrics.service
systemd/laimory-elasticsearch-metrics.timer
systemd/laimory-filebeat-metrics.service
systemd/laimory-filebeat-metrics.timer
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

dev WAS의 Filebeat HTTP stats는 `127.0.0.1:5066`에만 열고,
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

## Discord smoke test

실제 application/infra 장애를 만들지 않고 synthetic expression으로 firing과 resolved를 확인한다.
메시지는 alert/status/severity/environment/job/instance, 짧은 요약과 runbook만 포함하며 raw log,
request/response body, transactionId, user/task/FID, 좌표, exception message를 포함하지 않는다.

```bash
cd /opt/laimory-monitoring
read -rp 'Grafana admin username [admin]: ' GRAFANA_ADMIN_USER
GRAFANA_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}

sudo install -m 0644 grafana/smoke/smoke-rule.firing.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/grafana/api/admin/provisioning/alerting/reload
# Discord firing 도착 확인

sudo install -m 0644 grafana/smoke/smoke-rule.resolved.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/grafana/api/admin/provisioning/alerting/reload
# Discord resolved 도착 확인

sudo install -m 0644 grafana/smoke/smoke-rule.delete.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u "$GRAFANA_ADMIN_USER" -X POST \
  http://localhost:3000/grafana/api/admin/provisioning/alerting/reload
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

DNS, TLS 만료, nginx, `/status` 응답을 분리해 확인한다. `/status`가 성공해도 Redis, Kakao, S3까지
ready라는 뜻은 아니다.

### TLS certificate expiry

Overview의 남은 시간을 확인한 뒤 dev WAS에서 `sudo certbot certificates`,
`sudo systemctl status certbot.timer`를 본다. 만료 30일 전 warning과 14일 전 critical은 겹치지 않는다.
필요하면 `sudo certbot renew --dry-run` 후 실제 갱신을 수행하고 `sudo nginx -t`,
`sudo systemctl reload nginx`를 거쳐 blackbox의 새 만료 시각을 확인한다.

### HTTP errors or latency

Overview에서 최소 traffic 조건과 status/URI를 확인한 뒤 Logs dashboard에서 같은 시간대를 좁힌다.
원문·body 심층 분석은 Kibana로 이동한다. alert/Discord에는 원문을 복사하지 않는다.

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

### Timeline PHOTO delete backlog

Overview의 pending 수와 oldest age를 먼저 확인하고, enqueue 대비 attempt 성공/실패 발생률을 비교한다.
worker가 꺼져 있어도 두 gauge는 MySQL job table에서 계속 관측된다. dev WAS에서는 전체 환경을 출력하지 말고
현재 container의 `TIMELINE_PHOTO_DELETE_WORKER_ENABLED` 한 값만 확인한다.

worker flag를 바꿀 때는 host `.env`를 수정한 뒤 deploy workflow를 다시 실행하거나 기존 container를
stop/remove하고 동일한 `docker run --env-file` 인자로 새로 만들어야 한다. `docker restart`는 생성 당시
환경을 재사용하므로 변경된 `.env`를 읽지 않는다.

flag가 true인데 backlog가 줄지 않으면 MySQL/Hikari 상태, S3/IAM 오류, delete attempt 실패와 batch duration을
차례로 확인한다. 실패 job과 그 FK가 가리키는 원문 PHOTO Item은 다음 주기에 재시도되는 복구 권위이므로
둘 중 하나를 수동 삭제하거나 object key를 로그·alert에 복사하지 않는다. monitoring 자산 변경은 앱 자동
배포에 포함되지 않으므로 기존 provisioning 파일을 백업한 뒤 자산을 반영하고 Grafana provisioning
reload/restart 절차를 따른다.

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
`sudo systemctl start laimory-monitoring`으로 secret validator를 통과시킨다. `/grafana/`를 개방했다면
dev WAS에서 `/usr/local/sbin/laimory-grafana-proxy disable`로 Grafana include만 제거해 Kibana를
보존한다.
