# Laimory 인프라 (Terraform)

Laimory 백엔드 인프라를 코드로 관리한다. **AWS Innovation Sandbox 계정**은 리스 만료 시
전 리소스가 삭제(nuke)되므로, 이 코드로 `terraform apply` 한 번에 전 스택을 재현한다.

## 구성

| 파일 | 내용 |
|---|---|
| `versions.tf` / `providers.tf` | provider·로컬 backend |
| `variables.tf` / `locals.tf` / `terraform.tfvars` | 변수·계산값 |
| `network.tf` | VPC·서브넷·IGW·NAT·라우트·S3 게이트웨이 엔드포인트 |
| `security_groups.tf` | was / db / redis / ai SG |
| `iam.tf` | EC2 인스턴스 role·profile, GitHub OIDC role·provider |
| `ec2.tf` + `user_data/` | WAS(dev/prod)·MySQL·Redis·AI + 부트스트랩 스크립트 |
| `storage_cdn.tf` | S3 photos·binlog 백업 버킷·OAC·CloudFront·ECR |
| `dns.tf` | Route53 laimory.app 존 + 환경별 API 도메인 A 레코드 |
| `outputs.tf` | 인스턴스ID·CF도메인·버킷명·role ARN·NS 등 |

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
`deploy.yml` 과 앱 `.env` 반영에 사용한다(자세한 절차는 `.claude/plans/splendid-spinning-allen.md`).

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

## 앱 `.env` 필수 키 (WAS 박스 `/home/ubuntu/app/.env`)

user_data는 최초 부팅 시 인프라 유래 값만 시드한다. **아래 앱 secret 키들은 terraform을 거치지
않으므로**(state 노출 방지 + 기존 박스엔 user_data 재실행이 없음) SSM으로 직접 추가·갱신한다.
이 키들이 빠지면 앱이 fail-fast로 기동에 실패한다. ⚠️ 현재 `deploy.yml`은 새 컨테이너를 올리기 전에
기존 컨테이너를 `docker stop`/`rm` 하므로, 키 누락 시 기존 컨테이너도 이미 내려가 **다운타임이 난다** —
stop 전에 필수 키를 검사하는 pre-flight는 토큰 코어 PR(#111, JWT_SECRET이 필수가 되는 시점)에서 추가한다.

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
Route53 존은 재생성 시 **NS가 바뀌므로** 레지스트라 위임도 다시 해야 한다(위 runbook 1~2).
