# Laimory dev monitoring

Prometheus, Grafana, blackbox exporter와 MySQL/Redis exporter를 private dev monitoring EC2 한 대에서
실행하는 재구축 자산이다. 실제 AWS 반영은 `terraform/README.md`의 Console/SSM runbook을 따른다.
Terraform은 recipe이며 살아 있는 dev에 blanket/target `terraform apply`를 하지 않는다.

## 구성과 범위

- Prometheus: 30초 수집, TSDB 7일 또는 12GB 중 먼저 도달한 제한
- Grafana: Prometheus metrics와 read-only Elasticsearch dev log datasource
- blackbox exporter: public dev HTTPS `/status`를 60초마다 확인
- node_exporter: monitoring, dev WAS, dev MySQL, Redis, ELK의 private IP:9100
- central exporter: USAGE-only dev MySQL 계정과 INFO/PING-only Redis ACL 계정
- dashboard: `Laimory / Overview`, `JVM & Spring`, `Infrastructure`, `Logs`
- alert: target/probe, HTTP 오류·지연, JVM/Hikari, host disk/memory, MySQL/Redis, Prometheus self-health
- notification: Grafana native Discord contact point, firing과 resolved 모두 전송

Grafana 3000만 loopback과 monitoring private IP에 publish한다. Prometheus 9090, blackbox 9115,
mysqld exporter 9104, redis exporter 9121은 Docker network에만 expose한다. `/status`는 DB 중심
health이며 Redis와 외부 연동까지 포괄하는 readiness가 아니다.

초기 resource limit은 Prometheus 2GiB, Grafana 768MiB, central exporter 각각 192MiB다. dashboard
refresh는 30초다. 24시간 관찰에서 host memory 75% 초과가 15분 이상 반복되거나 OOM/restart가
생기면 collector와 label을 먼저 줄인다. active series 10,000 초과, root disk 70% 초과, scrape
duration이 interval의 50% 이상인 상태가 계속되면 원인을 줄인 뒤에도 해소되지 않을 때 t3.large를
별도 변경으로 검토한다. EC2 CPU credit은 이 stack이 아니라 CloudWatch에서 확인한다.

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
private IPv4를 얻어 그 주소의 9100에만 bind하며 textfile collector는 켜지 않는다.

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
{
  printf 'header = "Authorization: ApiKey '
  sudo cat /opt/laimory-monitoring/secrets/elasticsearch_api_key
  printf '"\n'
} |
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
```

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

## Discord smoke test

실제 application/infra 장애를 만들지 않고 synthetic expression으로 firing과 resolved를 확인한다.
메시지는 alert/status/severity/environment/job/instance, 짧은 요약과 runbook만 포함하며 raw log,
request/response body, transactionId, user/task/FID, 좌표, exception message를 포함하지 않는다.

```bash
cd /opt/laimory-monitoring

sudo install -m 0644 grafana/smoke/smoke-rule.firing.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u admin -X POST \
  http://localhost:3000/grafana/api/admin/provisioning/alerting/reload
# Discord firing 도착 확인

sudo install -m 0644 grafana/smoke/smoke-rule.resolved.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u admin -X POST \
  http://localhost:3000/grafana/api/admin/provisioning/alerting/reload
# Discord resolved 도착 확인

sudo install -m 0644 grafana/smoke/smoke-rule.delete.yml \
  grafana/provisioning/alerting/smoke-rule.yml
curl -fsS -u admin -X POST \
  http://localhost:3000/grafana/api/admin/provisioning/alerting/reload
sudo rm /opt/laimory-monitoring/grafana/provisioning/alerting/smoke-rule.yml
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

### HTTP errors or latency

Overview에서 최소 traffic 조건과 status/URI를 확인한 뒤 Logs dashboard에서 같은 시간대를 좁힌다.
원문·body 심층 분석은 Kibana로 이동한다. alert/Discord에는 원문을 복사하지 않는다.

### JVM or Hikari pressure

JVM & Spring에서 heap/GC, pending connection, acquire timeout을 함께 본다. 단발 GC나 traffic 없는
비율은 alert하지 않는다. DB connection pressure와 같이 발생하면 DB부터 확인한다.

### Host resource pressure

Infrastructure에서 실제 filesystem과 `MemAvailable`을 확인한다. tmpfs/overlay 같은 pseudo filesystem은
disk alert에서 제외된다. collector/cardinality를 줄여도 medium의 memory 75%가 지속되면 별도 resize
변경을 검토한다.

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

exporter rollback은 Prometheus target/job을 먼저 제거·reload한 뒤 각 host에서 node_exporter를
disable/remove하고 MySQL/Redis monitoring identity를 lock/off한다. stack rollback은
`sudo systemctl stop laimory-monitoring`으로 수행하며 TSDB/Grafana volume은 보존한다. 다시 올릴 때는
`sudo systemctl start laimory-monitoring`으로 secret validator를 통과시킨다. `/grafana/`를 개방했다면
dev WAS에서 `/usr/local/sbin/laimory-grafana-proxy disable`로 Grafana include만 제거해 Kibana를
보존한다.
