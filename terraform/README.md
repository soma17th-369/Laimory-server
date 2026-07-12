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

**DNS는 현재 가비아(Gabia)에서 관리한다** — route53 코드는 제거됨(향후 route53 이전 시 재도입).
기존 WAS 박스는 `user_data_replace_on_change=false`라 apply로 재생성되지 않으므로, **살아있는 박스의
nginx/certbot은 SSM으로 수동 적용**한다(user_data 변경분은 새 박스 재현용). 도메인:
prod=`laimory.app`(apex), dev=`dev.laimory.app`.

1. 가비아 DNS에서 A 레코드 설정: `dev.laimory.app` → dev WAS EIP, `laimory.app` → prod WAS EIP
   (EIP는 `terraform output was_public_ips`).
2. 전파 확인: `dig +short dev.laimory.app` 이 dev WAS EIP를 반환할 때까지 대기(TTL 수 분~수 시간).
3. 기존 박스에 SSM으로 nginx 설정 + certbot 적용 (dev/prod 각각, `<DOMAIN>`·`<EMAIL>` 치환):
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
4. 확인: `curl -I https://dev.laimory.app/status` → 200(유효 인증서), `curl -I http://dev.laimory.app/status` → 301.
   갱신은 `certbot.timer`가 자동 처리(`systemctl list-timers | grep certbot` 로 확인).

> nginx no-query 로그 설정(위 log_format/access_log 부분)은 `deploy.yml`이 배포마다 **멱등 가드로 자동
> 적용**한다 — 로그인 code가 쿼리로 나가는 앱 버전이 로그 설정 없는 박스에 배포되는 일을 구조적으로 막는다.
> 수동 runbook에서 이 부분을 빠뜨려도 다음 배포에서 자동 보정된다(certbot·server_name은 여전히 수동).

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
사설 `10.0.32.13`)**. 앱은 이미 JSON 로그를 stdout 으로 뱉으므로 Logstash 는 없다. 평소 EC2 **stop**,
볼 때만 **start** 운용(데이터는 named volume `esdata` 로 생존). Kibana 는 dev-was nginx 서브패스
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

# (2) Kibana 리버스 프록시 스니펫. 'NGINX' single-quote 로 $host 등 nginx 변수의 셸 확장을 막는다.
sudo install -d /etc/nginx/snippets
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

### 5. stop/start 운용

```bash
aws ec2 stop-instances  --profile sandbox --instance-ids "$(terraform -chdir=terraform output -raw elk_instance_id)"
aws ec2 start-instances --profile sandbox --instance-ids "$(terraform -chdir=terraform output -raw elk_instance_id)"
```

start 시 dockerd 가 es/kibana 를 `restart:unless-stopped` 로 자동 복구(setup 은 재실행 안 함, green 까지 ~2~3분).
꺼져 있으면 `/kibana` 는 502 → start 후 접속. 비상용으로 SSM 포트포워딩도 가능:
`aws ssm start-session --profile sandbox --target <elk_instance_id> --document-name AWS-StartPortForwardingSession --parameters portNumber=5601,localPortNumber=5601`.
ELK off 동안 WAS Filebeat 는 재시도하다 복귀 시 registry 지점부터 backfill(단 WAS json-file 버퍼 30MB/컨테이너 내에서만).

## 앱 `.env` 필수 키 (WAS 박스 `/home/ubuntu/app/.env`)

user_data는 최초 부팅 시 인프라 유래 값만 시드한다. **아래 앱 secret 키들은 terraform을 거치지
않으므로**(state 노출 방지 + 기존 박스엔 user_data 재실행이 없음) SSM으로 직접 추가·갱신한다.
이 키들이 빠지면 앱이 fail-fast로 기동에 실패한다. 현재 `deploy.yml`은 기존 컨테이너를 내리기 전에
`JWT_SECRET` 길이와 Google/Kakao OAuth client key 4개를 preflight한다.
`DB_*`, `REDIS_*`, `KAKAO_REST_API_KEY`는 아직 preflight 대상이 아니며,
이 값이 빠지면 기존 컨테이너 제거 후 새 앱 기동이 실패할 수 있다. health 실패 시 자동 rollback도 없다.

| 키 | 출처 | 비고 |
|---|---|---|
| `AWS_REGION` `DB_*` `REDIS_*` `PHOTO_*` | user_data 시드 | 인프라 유래 |
| `KAKAO_REST_API_KEY` | 수동 | 지오코딩용 (기존) |
| `JWT_SECRET` | 수동 | 자체 JWT HS256 서명키, **32자 이상 랜덤** |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 수동 | env별 OAuth 클라이언트 분리 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 수동 | 카카오 로그인용 REST API 키 — 지오코딩 키와 별도 선언 |

## nuke 후 복구

계정이 회수되면 새 계정 프로필로 다시 `terraform apply`. 로컬 state는 새 계정용으로
비우고(`rm terraform.tfstate*` 또는 새 디렉터리) fresh apply 한다. `.tf` 코드는 git에 남아있다.
DNS는 가비아(Gabia) A 레코드로 관리하므로, nuke 후 새 WAS EIP를 확인해(`terraform output was_public_ips`)
가비아 A 레코드를 갱신하고 도메인/TLS runbook(certbot)을 재적용한다(위 "도메인/TLS 적용 runbook" 1~4).
