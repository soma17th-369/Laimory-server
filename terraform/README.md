# Laimory 인프라 (Terraform)

Laimory 백엔드 인프라를 코드로 관리한다. **AWS Innovation Sandbox 계정**은 리스 만료 시
전 리소스가 삭제(nuke)되므로, 이 코드로 `terraform apply` 한 번에 전 스택을 재현한다.

## 구성

| 파일 | 내용 |
|---|---|
| `versions.tf` / `providers.tf` | provider·로컬 backend |
| `variables.tf` / `locals.tf` / `terraform.tfvars` | 변수·계산값 |
| `network.tf` | VPC·서브넷·IGW·NAT·라우트·S3 게이트웨이 엔드포인트 |
| `security_groups.tf` | was / db / redis / ai / elk SG (+ dev bastion SSH) |
| `iam.tf` | EC2 인스턴스 role·profile, GitHub OIDC role·provider |
| `ec2.tf` + `user_data/` | WAS(dev/prod)·MySQL·Redis·AI·ELK(dev 로그수집) + 부트스트랩 스크립트 |
| `storage_cdn.tf` | S3 photos·binlog 백업 버킷·OAC·CloudFront·ECR |
| `outputs.tf` | 인스턴스ID·CF도메인·버킷명·role ARN 등 |

state는 **로컬**에 둔다(`*.tfstate` 는 gitignore). 새 계정마다 fresh state로 apply한다.

## 선행: 새 Sandbox 계정 프로필 셋업

apply 전에 새 계정에 접근할 AWS 프로필을 만든다(IAM Identity Center/SSO 기준 예시):

```bash
aws configure sso --profile sandbox
#   SSO start URL / region 입력 → 브라우저 인증 → 계정·역할 선택
aws sts get-caller-identity --profile sandbox   # 새 계정ID 확인
```

`terraform.tfvars` 의 `aws_profile` 을 이 프로필명(`sandbox`)으로 맞춘다.

## 사용

```bash
cd terraform
cp secrets.auto.tfvars.example secrets.auto.tfvars   # 비밀값 채우기
terraform init
terraform plan
terraform apply
```

apply 후 `terraform output` 으로 새 인스턴스ID·CloudFront 도메인·버킷명을 확인하고,
`deploy.yml` 과 앱 `.env` 반영에 사용한다. 배포의 현재 계약은
[deployment knowledge](../.agents/knowledge/codebase/operations/deployment.md)를,
Terraform 운용 원칙은 [infra recipe mode](../.agents/skills/infra-recipe-mode/SKILL.md)를 따른다.

## 도메인/TLS 적용 runbook

**DNS는 Route53에서 관리한다**(2026-07-14 가비아→Route53 NS 위임 완료, #112). 도메인 등록(소유)은
가비아에 그대로 있고 네임서버(DNS 권한)만 Route53로 위임했다. `dns.tf`는 존/레코드만 만든다.
기존 WAS 박스는 `user_data_replace_on_change=false`라 apply로 재생성되지 않으므로, **살아있는 박스의
nginx/certbot은 SSM으로 수동 적용**한다(user_data 변경분은 새 박스 재현용). 도메인:
prod=`laimory.app`(apex), dev=`dev.laimory.app`.

> 라이브 존에는 dev A 레코드만 손으로 만들어져 있다(prod 미가동 — 레시피 모드라 코드와의 드리프트는
> 정상). 코드(`dns.tf`)는 재구축 시 dev/prod 둘 다 만든다.

1. `terraform apply` → `terraform output route53_name_servers` 로 NS 4개 확인.
2. 가비아(도메인 레지스트라)에서 laimory.app 네임서버를 위 4개로 위임. **존을 재생성하면 NS 4개가
   바뀌므로**(nuke 복구 포함) 재위임도 매번 다시 필요하다.
3. 전파 확인: `dig +short dev.laimory.app` 이 dev WAS EIP를 반환할 때까지 대기(TTL 수 분~수 시간).
4. 기존 박스에 SSM으로 nginx 설정 + certbot 적용 (dev/prod 각각, `<DOMAIN>`·`<EMAIL>` 치환):
   ```bash
   aws ssm start-session --profile sandbox --target <instance-id>
   # 박스 안에서:
   sudo tee /etc/nginx/conf.d/log_format_noquery.conf > /dev/null <<'EOF'
   log_format noquery '$remote_addr - $remote_user [$time_local] "$request_method $uri $server_protocol" $status $body_bytes_sent "$http_user_agent"';
   EOF
   sudo sed -i 's/server_name _;/server_name <DOMAIN>;/' /etc/nginx/sites-available/laimory
   # access_log 삽입은 재실행 시 중복되지 않게 grep 가드(SSM 재시도 대비 idempotent).
   grep -q 'access_log .*noquery' /etc/nginx/sites-available/laimory \
     || sudo sed -i '/client_max_body_size/a\    access_log /var/log/nginx/access.log noquery;' /etc/nginx/sites-available/laimory
   sudo nginx -t && sudo systemctl reload nginx
   sudo apt-get install -y certbot python3-certbot-nginx
   sudo certbot --nginx -d <DOMAIN> --non-interactive --agree-tos -m <EMAIL> --redirect
   ```
5. 확인: `curl -I https://dev.laimory.app/status` → 200(유효 인증서), `curl -I http://dev.laimory.app/status` → 301.
   갱신은 `certbot.timer`가 자동 처리(`systemctl list-timers | grep certbot` 로 확인).

> nginx no-query 로그 설정(위 log_format/access_log 부분)은 `deploy.yml`이 배포마다 **멱등 가드로 자동
> 적용**한다 — 로그인 code가 쿼리로 나가는 앱 버전이 로그 설정 없는 박스에 배포되는 일을 구조적으로 막는다.
> 수동 runbook에서 이 부분을 빠뜨려도 다음 배포에서 자동 보정된다(certbot·server_name은 여전히 수동).

## WAS 스왑 2GB — 기존 박스는 수동 적용

스왑 없는 1~2GB WAS 박스는 메모리 스파이크(apt-daily 등) 때 OOM 킬 대신 페이지 회수 라이브락으로
박스 전체(SSM 포함)가 동결된다. 신규/재생성 박스는 `user_data/was.sh.tftpl`의 스왑 블록이 자동
재현하고, **기존 박스는 SSM으로 한 번 수동 적용**한다(dev-was는 2026-07-17 적용 완료, prod-was 미적용):

```bash
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

## dev DB 읽기전용 접근 (bastion) — 기존 박스는 수동 적용

`terraform`이 관리하는 것은 **dev bastion SG(22 ← allowlist CIDR)** 와 **user_data(신규 박스 재현용)** 뿐이다.
기존 dev WAS/mysql 박스는 `ignore_changes=[user_data]`라 **`terraform apply`(`-target` 포함)로는
아래 on-host 리소스가 생기지 않는다.** `dbviewer` SSH 유저와 `readonly` DB 계정은 **살아있는 박스에
SSM으로 한 번 수동 적용**한다(신규/재생성 박스는 user_data가 자동 재현).

- **readonly DB 계정** (dev-mysql): `user_data/mysql.sh.tftpl` 의 `env=="dev"` 블록과 동일한 SQL
  (`CREATE USER`/`ALTER USER`/`GRANT SELECT ON laimory.*`)을 `sudo mysql` 로 실행.
- **dbviewer SSH 유저 + sshd** (dev WAS): `user_data/was.sh.tftpl` 의 `env=="dev"` 블록과 동일
  (`systemctl enable --now ssh`, `dbviewer` nologin, `authorized_keys` 에
  `restrict,port-forwarding,permitopen="<dev-mysql-ip>:3306" <공개키>`).

두 user_data 블록이 SSM 수동 적용의 **단일 기준(source of truth)** 이다. 값(비밀번호·공개키·IP)은
`terraform.tfvars`·`secrets.auto.tfvars` 와 동일하게 맞춘다.

## ELK 로그 수집 (dev) — 박스 birth + 기존 dev-was 는 수동 적용

dev 로그 수집 스택 = **Filebeat(WAS) + Elasticsearch + Kibana(신규 ELK 박스 `laimory-dev-elk-01`,
사설 `10.0.32.13`)**. 앱은 이미 JSON 로그를 stdout 으로 뱉으므로 Logstash 는 없다. **스팟 인스턴스
상시 가동**(persistent+stop, #149 — 운용 제약은 아래 5번). Kibana 는 dev-was nginx 서브패스
리버스 프록시로 `https://dev.laimory.app/kibana` 에서 연다(ELK 박스 공개 노출 0 — 9200·5601 은 WAS SG 만).

`terraform` 이 관리하는 것은 **ELK 박스·SG·S3 부트스트랩(compose/ILM/템플릿/filebeat.yml)** 과
**user_data(신규 박스 재현용)** 뿐이다. 기존 dev-was 박스는 `ignore_changes=[user_data]` 라
`terraform apply` 로는 Filebeat·nginx `/kibana` 가 생기지 않으므로 **살아있는 박스에 SSM 으로 한 번
수동 적용**한다. dev-was 의 user_data(`was.sh.tftpl` 의 `env=="dev"` ELK 블록)가 이 SSM 절차의 단일 기준이다.

### 1. secrets 채우기

`secrets.auto.tfvars` 에 ES 비번 3개(`elk_elastic_password`/`elk_kibana_password`/`elk_filebeat_password`)를
추가한다(문자셋 규칙은 `secrets.auto.tfvars.example` 참고).

### 2. ELK 박스 birth (레시피 모드 — blanket apply 금지)

살아있는 dev 에 전체 `terraform apply` 를 하지 않는다. **ELK 리소스만 `-target` 으로 좁혀** 생성한다.
⚠️ SG 규칙은 SG 의 dependent 라 `-target=aws_security_group.elk` 만으로는 **안 딸려온다** — 규칙도 각각 명시.
apply 직전 Docker 태그 3종 존재 확인(없거나 더 최신 8.19 패치가 있으면 `variables.tf`·compose·filebeat run 동시 갱신):

```bash
docker manifest inspect docker.elastic.co/elasticsearch/elasticsearch:8.19.18 >/dev/null && echo ok
docker manifest inspect docker.elastic.co/kibana/kibana:8.19.18 >/dev/null && echo ok
docker manifest inspect docker.elastic.co/beats/filebeat:8.19.18 >/dev/null && echo ok
```

```bash
cd terraform
TARGETS="-target=aws_security_group.elk \
  -target=aws_vpc_security_group_ingress_rule.elk_es \
  -target=aws_vpc_security_group_ingress_rule.elk_kibana \
  -target=aws_vpc_security_group_egress_rule.elk_all \
  -target=aws_s3_object.elk_compose  -target=aws_s3_object.elk_ilm \
  -target=aws_s3_object.elk_template -target=aws_s3_object.elk_filebeat \
  -target=aws_instance.elk"
terraform plan  $TARGETS   # ELK 리소스만 create, 기존 WAS/MySQL/Redis/AI·deps 변경 0 인지 확인
terraform apply $TARGETS
```

plan 에서 기존 박스 변경(drift)이 뜨면 apply 하지 말고 **콘솔/CLI 로 박스만 직접 런치**(렌더된
user_data 주입)하고 코드는 레시피로 남긴다. 박스 생성 시 user_data 가 첫 부팅에 docker 설치 +
`docker compose up`(ES/Kibana/setup) 을 자동 실행한다 → ES green 대기(~2~3분).

### 3. 기존 dev-was 에 Filebeat + nginx `/kibana` 적용 (일회성 SSM)

기존 dev-was 는 user_data 재실행이 없으므로 살아있는 박스에 한 번 수동 적용한다. **운영자가 로컬에서**
SSM 세션을 열고 박스 안에서 실행(GHA/deploy.yml 아님 — 그건 앱 배포 전용). `was.sh.tftpl` 의 `env=="dev"`
ELK 블록과 동일한 내용이다. 전제: dev-was 는 이미 certbot 443 블록이 있다(도메인/TLS runbook 완료 상태).

```bash
aws ssm start-session --profile sandbox --target <dev-was-instance-id>   # terraform output was_instance_ids
```
```bash
# ── 박스 안에서 (sudo). 값 치환: FILEBEAT_PW=secrets.auto.tfvars 의 elk_filebeat_password ──
FILEBEAT_PW='<elk_filebeat_password>'
ELK_IP=10.0.32.13
BACKUP_BUCKET=$(sudo aws s3 ls | grep db-binlog | awk '{print $3}')   # 또는 terraform output backup_bucket

# (1) Filebeat: 앱 컨테이너 stdout(JSON) → ELK ES 직송. --user root 필수(컨테이너 로그 root:root 0600).
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/elk/filebeat.yml" /home/ubuntu/filebeat.yml
sudo docker rm -f filebeat 2>/dev/null || true
sudo docker run -d --name filebeat --restart unless-stopped --network host --user root \
  -e FILEBEAT_PASSWORD="$FILEBEAT_PW" \
  -v /home/ubuntu/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro \
  -v /var/lib/docker/containers:/var/lib/docker/containers:ro \
  -v filebeat-data:/usr/share/filebeat/data \
  docker.elastic.co/beats/filebeat:8.19.18 filebeat -e --strict.perms=false

# (2) Kibana 리버스 프록시 스니펫. 활성 Grafana include가 있으면 overwrite 전 상태를 기억해 보존한다.
#     'NGINX' single-quote 로 $host 등 nginx 변수의 셸 확장을 막는다.
sudo install -d /etc/nginx/snippets
GRAFANA_INCLUDE='include /etc/nginx/snippets/laimory-grafana.conf;'
GRAFANA_PROXY_WAS_ACTIVE=false
if sudo grep -Fqx "$GRAFANA_INCLUDE" /etc/nginx/snippets/laimory-extra.conf 2>/dev/null; then
  GRAFANA_PROXY_WAS_ACTIVE=true
fi
sudo install -d -m 0755 /var/backups/laimory-nginx
sudo test ! -f /etc/nginx/snippets/laimory-extra.conf \
  || sudo cp -a /etc/nginx/snippets/laimory-extra.conf \
    "/var/backups/laimory-nginx/laimory-extra.conf.$(date -u +%Y%m%dT%H%M%S%N)"
sudo tee /etc/nginx/snippets/laimory-extra.conf > /dev/null <<'NGINX'
location = /kibana { return 302 /kibana/; }
location /kibana/ {
    proxy_pass http://ELK_IP_PLACEHOLDER:5601;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    # allow 1.2.3.4/32; deny all;   # (선택) Kibana 열람 허용 IP. 생략 시 Kibana 로그인만으로 방어.
}
NGINX
sudo sed -i "s/ELK_IP_PLACEHOLDER/$ELK_IP/" /etc/nginx/snippets/laimory-extra.conf
if [[ "$GRAFANA_PROXY_WAS_ACTIVE" == true ]]; then
  printf '\n%s\n' "$GRAFANA_INCLUDE" | sudo tee -a /etc/nginx/snippets/laimory-extra.conf >/dev/null
fi

# (3) certbot 443 블록에 include (idempotent). ssl_certificate_key 라인 뒤 = 443 블록 안에 삽입.
grep -q 'laimory-extra.conf' /etc/nginx/sites-available/laimory \
  || sudo sed -i '/ssl_certificate_key/a\    include /etc/nginx/snippets/laimory-extra.conf;' /etc/nginx/sites-available/laimory
sudo nginx -t && sudo systemctl reload nginx
sudo docker ps --filter name=filebeat   # Up 확인
```

> ⚠️ nginx `include` 는 **certbot 이 만든 `listen 443 ssl` server 블록 안에** 들어가야 한다(`ssl_certificate_key`
> 라인 뒤에 삽입하는 이유). 80 블록에만 넣으면 실제 HTTPS 접속에서 안 먹는다.

### 4. Kibana 접속 + Data View

`https://dev.laimory.app/kibana` → `suhyun444`(또는 예약유저 `elastic`) / `<elk_elastic_password>` 로그인 →
Stack Management > Data Views 에서 **`laimory-dev-*`**(time field `@timestamp`) 생성.
E2E: `curl -i https://dev.laimory.app/api/v1/intro` 응답 헤더의 `Transaction-Id` 값을 Kibana 에서 검색하면
그 요청의 액세스 로그(`event:http_request_completed`, `status`, `latencyMs`, `path`)와 앱 라인이 함께 보인다.
에러 요청은 `errorCode`(클라이언트 계약)·`exceptionType`(내부 실패 사유)·`errorDetail` 필드까지 keyword로
검색돼야 한다 — text+keyword 멀티필드로 보이면 template보다 앱이 먼저 배포돼 dynamic mapping으로 굳은 것.

### 5. 스팟 운용 (persistent+stop, 상시 가동)

ELK 박스는 스팟이라(온디맨드 24/7 대비 ~60-70% 절감) 운용 제약이 다르다:

- **수동 stop 불가** — `aws ec2 stop-instances` 는 `UnsupportedOperation`. 종전 "평소 stop, 볼 때만
  start" 운용은 폐기하고 상시 가동한다. 내리려면 terminate(=재생성) 뿐.
- **용량 회수(interruption) 시** persistent+stop 이라 terminate 가 아니라 **stop** 되고(루트 EBS·고정
  IP `10.0.32.13` 보존), 용량이 돌아오면 스팟 서비스가 **자동 재시작**한다. 재시작 후 dockerd 가
  es/kibana 를 `restart:unless-stopped` 로 자동 복구(setup 은 재실행 안 함, green 까지 ~2~3분).
- ELK 가 내려간 동안 `/kibana` 는 502. WAS Filebeat 는 재시도하다 복귀 시 registry 지점부터
  backfill(단 WAS json-file 버퍼 30MB/컨테이너 내에서만).
- **destroy/재생성 시 스팟 요청 확인** — persistent 요청은 인스턴스만 terminate 하면 요청이 살아남아
  좀비 인스턴스를 재기동한다. provider >= 5.86(현 lock 5.100.0)은 `terraform destroy` 가 요청까지
  취소하지만, 콘솔에서 terminate 했다면 스팟 요청(EC2 > Spot Requests)을 직접 cancel 한다.
- 비상 접근용 SSM 포트포워딩은 그대로:
  `aws ssm start-session --profile sandbox --target <elk_instance_id> --document-name AWS-StartPortForwardingSession --parameters portNumber=5601,localPortNumber=5601`.

기존 온디맨드 박스에서 스팟으로 교체할 때(스팟 여부는 launch 시점 속성이라 in-place 전환 불가):

```bash
cd terraform
terraform plan  -destroy -target=aws_instance.elk   # elk 1대만 destroy 인지 확인
terraform apply -destroy -target=aws_instance.elk   # ES 로그 데이터 소실 허용(dev 로그뿐)
terraform plan  -target=aws_instance.elk            # elk 1대만 create(spot) 인지 확인
terraform apply -target=aws_instance.elk
```

재생성 후 Filebeat(WAS) 는 IP 불변이라 무변경으로 재접속하고, Kibana Data View 는 ES 볼륨이 새로
비었으므로 4번 절차로 다시 만든다.

## Prometheus + Grafana 모니터링 (dev) — Console/SSM 수동 반영

`aws_instance.monitoring`과 관련 IAM/SG/S3 object는 **nuke 후 재구축 recipe**다. 살아 있는 dev에는
blanket/target `terraform apply`를 하지 않는다. Console/SSM으로 같은 구성을 만들며, 수동 생성한
리소스는 Terraform state에 없으므로 나중에 apply하면 중복 생성·SG attachment 변경이 생길 수 있다.
향후 apply 전에는 먼저 import/정리 계획을 별도로 검토한다.

현재 recipe:

- private subnet[0], 고정 IP 후보 `10.0.32.14`, public IP 없음
- On-Demand `t3.medium`, 암호화 gp3 30GiB
- 전용 `laimory-monitoring-role`: SSM Core + `bootstrap/monitoring/*` GetObject만
- Prometheus 7일/12GB, Grafana, blackbox, central MySQL/Redis exporter
- monitoring/dev WAS/dev MySQL/Redis/ELK에는 private-IP-bound node_exporter
- Prometheus metrics dashboard 3개와 Elasticsearch dev log dashboard 1개, Grafana native Discord alert
- Grafana 3000만 dev WAS identity SG에서 접근; 9090/9104/9115/9121은 host에 publish하지 않음
- secret을 쓰는 Grafana의 시작은 systemd가 소유하고 process 실패만 Docker `on-failure`로 복구
- `grafana_allowed_cidrs=[]`이면 dev nginx에 `/grafana/`를 만들지 않고 SSM-only

### 1. 사전 조회와 비밀 없는 bootstrap 업로드

AWS SSO 로그인 후 후보 IP가 비어 있는지 **launch 직전** 확인한다. 결과가 하나라도 있으면 다른 private
IP를 정하고 `monitoring_private_ip`와 target 렌더를 함께 바꾼다.

```bash
aws sso login --profile sandbox
aws ec2 describe-network-interfaces --profile sandbox --region ap-northeast-2 \
  --filters Name=addresses.private-ip-address,Values=10.0.32.14 \
  --query 'NetworkInterfaces[].NetworkInterfaceId'
```

monitoring role에는 `ListBucket`이 없으므로 user_data는 object를 정확한 key로 하나씩 받는다. 먼저 운영자
권한으로 아래 비밀 없는 자산을 backup bucket에 올린다. target template에는 현재 live private IP를
넣어 임시 디렉터리에 렌더한다.

업로드하기 전에 기존 공용 EC2 role의 backup `s3:PutObject` resource가 실제 binlog 경로
(`${backup-bucket-arn}/binlog/*`)로 제한됐는지 Console에서 축소·확인한다. bucket 전체 write가 남아
있으면 기존 WAS/DB/ELK가 실행형 `bootstrap/monitoring/*`를 덮어쓸 수 있으므로 업로드하지 않는다.

```bash
BOOTSTRAP_TMP=$(mktemp -d)
export dev_was_private_ip='<current-dev-was-private-ip>'
export monitoring_private_ip='10.0.32.14'
export dev_mysql_private_ip='10.0.32.12'
export redis_private_ip='10.0.32.11'
export elk_private_ip='10.0.32.13'
export dev_api_domain='dev.laimory.app'

envsubst '${dev_was_private_ip}' \
  < deploy/monitoring/prometheus/application-targets.yml.tftpl \
  > "$BOOTSTRAP_TMP/application.yml"
envsubst '${monitoring_private_ip} ${dev_was_private_ip} ${dev_mysql_private_ip} ${redis_private_ip} ${elk_private_ip}' \
  < deploy/monitoring/prometheus/node-targets.yml.tftpl \
  > "$BOOTSTRAP_TMP/node.yml"
envsubst '${dev_api_domain}' \
  < deploy/monitoring/prometheus/probe-targets.yml.tftpl \
  > "$BOOTSTRAP_TMP/probe.yml"

BACKUP_BUCKET='<terraform output -raw backup_bucket 값>'
while IFS= read -r asset; do
  aws s3 cp "deploy/monitoring/$asset" \
    "s3://$BACKUP_BUCKET/bootstrap/monitoring/$asset" \
    --profile sandbox --only-show-errors
done <<'ASSETS'
docker-compose.yml
prometheus/prometheus.yml
blackbox/blackbox.yml
node-exporter/install.sh
node-exporter/uninstall.sh
grafana/provisioning/datasources/prometheus.yml
grafana/provisioning/datasources/elasticsearch.yml
grafana/provisioning/dashboards/provider.yml
grafana/provisioning/dashboards/json/laimory-overview.json
grafana/provisioning/dashboards/json/laimory-jvm-spring.json
grafana/provisioning/dashboards/json/laimory-infrastructure.json
grafana/provisioning/dashboards/json/laimory-logs.json
grafana/provisioning/alerting/contact-points.yml
grafana/provisioning/alerting/notification-policy.yml
grafana/provisioning/alerting/rules.yml
grafana/provisioning/alerting/templates.yml
grafana/smoke/smoke-rule.firing.yml
grafana/smoke/smoke-rule.resolved.yml
grafana/smoke/smoke-rule.delete.yml
scripts/install-secret.sh
scripts/validate-secrets.sh
scripts/configure-mysql-exporter-user.sh
scripts/configure-redis-exporter-user.sh
nginx/manage-grafana-proxy.sh
systemd/laimory-monitoring.service
ASSETS

aws s3 cp "$BOOTSTRAP_TMP/application.yml" "s3://$BACKUP_BUCKET/bootstrap/monitoring/prometheus/targets/application.yml" --profile sandbox
aws s3 cp "$BOOTSTRAP_TMP/node.yml" "s3://$BACKUP_BUCKET/bootstrap/monitoring/prometheus/targets/node.yml" --profile sandbox
aws s3 cp "$BOOTSTRAP_TMP/probe.yml" "s3://$BACKUP_BUCKET/bootstrap/monitoring/prometheus/targets/probe.yml" --profile sandbox
```

임시 target 파일에는 secret이 없지만 작업이 끝나면 임시 디렉터리를 폐기한다. S3 prefix에는 admin
비밀번호, Grafana secret key, exporter/Elasticsearch credential, Discord webhook을 절대 올리지 않는다.

### 2. Console에서 IAM·SG·EC2 생성

Terraform의 다음 리소스를 그대로 보고 Console에서 만든다.

1. `aws_iam_role.monitoring`/profile: SSM Core와 해당 bucket의
   `bootstrap/monitoring/*` `s3:GetObject`만 허용한다.
2. `monitoring`, `monitoring_proxy_source`, `monitoring_scrape_target`,
   `monitoring_dev_mysql` SG를 만든다.
3. identity SG는 dev에만 붙인다:
   - proxy source → dev WAS
   - scrape target → monitoring, dev WAS, dev MySQL, Redis, ELK
   - dev MySQL → dev MySQL
4. monitoring SG inbound 3000은 proxy source에서만, target 9090/9100과 dev MySQL 3306은 monitoring
   SG에서만 허용한다. 기존 Redis 6379와 ELK 9200에도 monitoring SG source rule을 추가한다.
5. private subnet[0]에 On-Demand t3.medium, 확인한 private IP, monitoring profile/SG,
   암호화 gp3 30GiB로 `laimory-dev-monitoring-01`을 만든다.

Console user data에는 `terraform/user_data/monitoring.sh.tftpl`을 아래 **비밀 없는 값만** 치환해 넣는다.
`grafana_allowed_cidrs`가 비어 있는 초기 상태는 localhost root URL과 insecure-cookie(false)를 쓰며,
3000은 SG와 loopback bind로만 접근된다.

```bash
export region='ap-northeast-2'
export backup_bucket='<backup bucket>'
export monitoring_private_ip='10.0.32.14'
export prometheus_version='v3.13.1'
export grafana_version='13.1.1'
export blackbox_exporter_version='v0.28.0'
export node_exporter_version='v1.12.1'
export node_exporter_linux_amd64_sha256='b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63'
export mysqld_exporter_version='v0.19.0'
export redis_exporter_version='v1.87.0-alpine'
export redis_exporter_username='laimory_monitoring'
export dev_mysql_private_ip='10.0.32.12'
export redis_private_ip='10.0.32.11'
export elk_private_ip='10.0.32.13'
export grafana_root_url='http://localhost:3000/grafana/'
export grafana_cookie_secure='false'
envsubst '${region} ${backup_bucket} ${monitoring_private_ip} ${prometheus_version} ${grafana_version} ${blackbox_exporter_version} ${node_exporter_version} ${node_exporter_linux_amd64_sha256} ${mysqld_exporter_version} ${redis_exporter_version} ${redis_exporter_username} ${dev_mysql_private_ip} ${redis_private_ip} ${elk_private_ip} ${grafana_root_url} ${grafana_cookie_secure}' \
  < terraform/user_data/monitoring.sh.tftpl > "$BOOTSTRAP_TMP/monitoring-user-data.sh"
```

부팅 후 monitoring host의 node_exporter와 secret 없는 Prometheus/blackbox만 자동 기동된다.
나머지 target은 아래 SSM 절차가 끝나기 전까지 DOWN이 정상이다.

### 3. SSM으로 exporter identity와 여섯 secret 구성

Git/Terraform/S3/명령 이력에 secret 원문을 넣지 않는다. Session Manager의 hidden prompt와
`deploy/monitoring/scripts/install-secret.sh`만 사용한다.

```bash
aws ssm start-session --profile sandbox --target <monitoring-instance-id>
```

설정 순서와 정확한 명령은 `deploy/monitoring/README.md`를 따른다.

1. monitoring, dev WAS, dev MySQL, Redis, ELK에 같은 pinned node_exporter installer를 SSM으로 실행한다.
2. dev MySQL에는 monitoring private IP-scoped `USAGE`-only 계정을 만들고 SELECT/DDL denial을 확인한다.
3. Redis에는 INFO/PING/CLIENT SETNAME만 허용한 별도 ACL 계정을 만들고 GET/SET/SCAN/SLOWLOG denial을
   `ACL DRYRUN`으로 확인한다.
4. monitoring host에 Grafana admin/secret key, MySQL CNF, Redis password JSON을 UID별 `0400` 파일로
   넣는다.
5. `laimory-dev-*`에 `monitor`, `read`, `view_index_metadata`만 가진 Elasticsearch API key를 만들고
   read true, write/delete false를 `_has_privileges`로 확인한다.
6. 사용자가 지정한 Discord channel webhook URL을 host secret 파일로 넣는다.
7. `validate-secrets.sh`, `systemctl start laimory-monitoring`, `docker compose ps` 순서로 확인한다.
8. synthetic smoke rule로 Discord firing/resolved를 모두 확인한 뒤 `deleteRules`로 제거한다.

secret이 하나라도 없거나 UID/mode가 다르면 systemd는 fail-closed한다. Grafana admin password는 최초
DB 생성 때 각인되고, stable `grafana_secret_key`는 datasource/contact credential 복호화에 계속 필요하다.
Discord 메시지는 raw log/body/transactionId/user/task/FID/좌표/exception 원문을 포함하지 않는다.

### 4. SSM-only 검증과 allowlist 개방

allowlist가 비어 있으면 standard port forwarding으로 접속한다.

```bash
aws ssm start-session --profile sandbox --target <monitoring-instance-id> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
```

브라우저에서 `http://localhost:3000/grafana/`로 확인한다. Prometheus UI는 publish하지 않는다. CIDR
목록이 비어 있으면 아래 개방 절차를 건너뛴다.

CIDR이 확정되면 먼저 monitoring host에서 public root URL과 secure cookie를 적용한다.

```bash
cd /opt/laimory-monitoring
sudo sed -i 's|^GRAFANA_ROOT_URL=.*|GRAFANA_ROOT_URL=https://dev.laimory.app/grafana/|' .env
sudo sed -i 's|^GRAFANA_COOKIE_SECURE=.*|GRAFANA_COOKIE_SECURE=true|' .env
sudo systemctl restart laimory-monitoring
```

그다음 dev WAS의 SSM 세션에서 secretless 관리 script를 받고 실행한다. CIDR은 하나 이상 전달해야 하며
script가 private monitoring IP와 IPv4 CIDR을 검증한다. 기존 `laimory-extra.conf`를
`/var/backups/laimory-nginx/`에 보존하고 별도 Grafana include만 추가하므로 Kibana location을
덮어쓰지 않는다. `nginx -t` 또는 reload가 실패하면 이전 파일을 자동 복원한다.

```bash
BACKUP_BUCKET='<backup bucket>'
sudo aws s3 cp "s3://$BACKUP_BUCKET/bootstrap/monitoring/nginx/manage-grafana-proxy.sh" \
  /usr/local/sbin/laimory-grafana-proxy
sudo chmod 0750 /usr/local/sbin/laimory-grafana-proxy
sudo /usr/local/sbin/laimory-grafana-proxy enable 10.0.32.14 \
  '<first-allowed-cidr>' '<additional-allowed-cidr>'
```

허용된 client에서는 `/grafana`와 `/grafana/`가 Grafana login으로 이동하고 `/kibana/`가 이전처럼
동작해야 한다. allowlist 밖 client에서는 `/grafana`와 `/grafana/`가 모두 403이어야 한다.

### 5. target/dashboard/alert 검증과 24시간 soak

- Prometheus의 `spring_boot`, `node`, `mysqld`, `redis`, `blackbox_https`, `grafana`, `prometheus`
  target이 모두 UP이고 scrape duration이 interval의 50% 미만인지 확인한다.
- `mysql_up=1`, `redis_up=1`, JVM/HTTP/Hikari와 #186 custom metric이 실제 dev 요청 후 생기는지 확인한다.
- dashboard 4개가 query error 없이 로드되고 Logs가 `laimory-dev-*`만 읽는지 확인한다.
- Discord synthetic rule의 firing/resolved와 허용 필드만 포함된 메시지를 확인하고 test rule을 삭제한다.
- 24시간 동안 RSS/OOM/restart, active series, disk 증가율, query latency를 dashboard에서 확인한다.
  `CPUCreditBalance`와 `CPUSurplusCreditsCharged`는 IAM/exporter를 늘리지 않고 CloudWatch에서 확인한다.
- live 수동 보정이 필요했다면 먼저 이 recipe/runbook에 동기화한다.

### 6. 중지와 rollback

```bash
sudo systemctl stop laimory-monitoring
```

`docker compose down -v`는 Prometheus/Grafana volume을 삭제하므로 실행하지 않는다. 설정 실패 시
stack을 stop해도 앱 API와 ELK는 독립적으로 계속 동작한다. 다시 올릴 때는
`sudo systemctl start laimory-monitoring`으로 secret gate를 다시 통과시킨다.

`/grafana/`를 이미 개방했다면 dev WAS에서 아래 명령으로 Grafana include만 제거한다. script가 backup,
`nginx -t`, reload와 실패 시 복원을 수행하며 Kibana는 보존한다.

```bash
sudo /usr/local/sbin/laimory-grafana-proxy disable
```

다시 SSM-only로 Grafana를 실행할 때는 monitoring host `.env`의 root URL을
`http://localhost:3000/grafana/`, secure cookie를 `false`로 되돌리고 systemd unit을 재시작한다. live
리소스 제거가 필요하면 attachment와 정확한 instance/SG/IAM target을 Console에서 다시 확인한 뒤 별도
승인하에 처리한다.

## 앱 `.env` 필수 키 (WAS 박스 `/home/ubuntu/app/.env`)

user_data는 최초 부팅 시 인프라 유래 값만 시드한다. **아래 앱 secret 키들은 terraform을 거치지
않으므로**(state 노출 방지 + 기존 박스엔 user_data 재실행이 없음) SSM으로 직접 추가·갱신한다.
이 키들이 빠지면 앱이 fail-fast로 기동에 실패한다. 현재 `deploy.yml`은 기존 컨테이너를 내리기 전에
`JWT_SECRET` 길이와 Google/Kakao OAuth client key 4개를 preflight하고, `.env`가
`APP_PUSH_MODE=firebase`면 Firebase service-account 파일 존재도 preflight한다.
`DB_*`, `REDIS_*`, `KAKAO_REST_API_KEY`는 아직 preflight 대상이 아니며,
이 값이 빠지면 기존 컨테이너 제거 후 새 앱 기동이 실패할 수 있다. health 실패 시 자동 rollback도 없다.

| 키 | 출처 | 비고 |
|---|---|---|
| `AWS_REGION` `DB_*` `REDIS_*` `PHOTO_*` | user_data 시드 | 인프라 유래 |
| `KAKAO_REST_API_KEY` | 수동 | 지오코딩용 (기존) |
| `JWT_SECRET` | 수동 | 자체 JWT HS256 서명키, **32자 이상 랜덤** |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 수동 | env별 OAuth 클라이언트 분리 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 수동 | 카카오 로그인용 REST API 키 — 지오코딩 키와 별도 선언 |
| `APP_PUSH_MODE` | 수동 | FCM 푸시 모드(`noop` 기본/`firebase`) — 아래 FCM runbook으로만 전환 |

## FCM 푸시(firebase 모드) 활성화 runbook — 기존 WAS 수동 적용

앱은 기본 `APP_PUSH_MODE=noop`(외부 발송 없음)으로 기동한다. firebase 모드는 Firebase service-account
JSON을 **파일로만** 전달한다(ADC) — credential 원문을 `.env` 값·Terraform 변수/state·Git·이미지에 넣지
않는다. user_data는 `/home/ubuntu/app/secrets` 디렉터리 골격만 만든다(신규 박스 재구축용). **살아있는
dev/prod에는 terraform apply를 하지 않는다** — 기존 박스는 아래 SSM 절차로만 반영한다.

선행조건: Firebase Console에서 Android 앱 패키지가 등록된 project의 service account 키 발급,
FCM HTTP v1 API 활성화, service account에 FCM 발송 권한(`roles/firebasecloudmessaging.admin`).

1. credential 파일 배치(SSM 세션에서; JSON은 로컬에서 base64로 복사해 붙인다 — 셸 히스토리에 원문 미노출):

   ```bash
   aws ssm start-session --profile sandbox --target <instance-id>
   sudo install -d -m 700 -o ubuntu -g ubuntu /home/ubuntu/app/secrets
   # 로컬에서: base64 < service-account.json | pbcopy  → 아래 heredoc에 붙여넣기
   base64 -d <<'B64' | sudo tee /home/ubuntu/app/secrets/firebase-service-account.json > /dev/null
   <붙여넣기>
   B64
   # 앱 컨테이너는 Dockerfile의 appuser(UID 1001)로 실행된다 — ubuntu(1000) 소유·0600이면 read-only
   # mount를 컨테이너가 못 읽어 기동이 실패한다. owner를 UID 1001로 두고 owner-read만 허용한다.
   sudo chown 1001 /home/ubuntu/app/secrets/firebase-service-account.json
   sudo chmod 0400 /home/ubuntu/app/secrets/firebase-service-account.json
   ```

2. `.env`에 모드 추가: `echo 'APP_PUSH_MODE=firebase' | sudo tee -a /home/ubuntu/app/.env`
3. dev는 다음 `deploy.yml` 배포가 pre-flight(파일 존재) → credential read-only mount
   (`/run/secrets/firebase-service-account.json`) + `GOOGLE_APPLICATION_CREDENTIALS` 주입까지 수행한다.
   즉시 반영하려면 배포를 재실행한다(`.env`만 바꿔서는 실행 중 컨테이너에 반영되지 않음).
4. 확인: 컨테이너 로그에 FirebaseApp 초기화 오류가 없고 `/api/v1/intro` 200. credential이 잘못되면
   앱이 fail-fast로 기동 실패한다(health check가 배포를 실패시킴 — 자동 rollback 없음).
5. **rollback**: `.env`의 `APP_PUSH_MODE=firebase` 줄 제거(→ noop) 후 재배포/컨테이너 재기동.
   FID 등록 API·DB는 유지된다(추후 재활성화 호환). credential 유출 의심 시 Google Cloud에서 키
   폐기·재발급 후 1번 절차로 파일 교체 — secret 값은 incident 문서·로그에 복제하지 않는다.

## nuke 후 복구

계정이 회수되면 새 계정 프로필로 다시 `terraform apply`. 로컬 state는 새 계정용으로
비우고(`rm terraform.tfstate*` 또는 새 디렉터리) fresh apply 한다. `.tf` 코드는 git에 남아있다.
DNS는 Route53(`dns.tf`)로 관리하므로 A 레코드는 apply가 새 EIP로 만들어 주지만, **새 존의 NS 4개는
이전과 다르므로 가비아에서 네임서버 재위임을 다시 해야 한다**. 이후 도메인/TLS runbook(certbot)을
재적용한다(위 "도메인/TLS 적용 runbook" 1~5).
