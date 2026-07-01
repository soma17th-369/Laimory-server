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
`deploy.yml` 과 앱 `.env` 반영에 사용한다(자세한 절차는 `.claude/plans/splendid-spinning-allen.md`).

## nuke 후 복구

계정이 회수되면 새 계정 프로필로 다시 `terraform apply`. 로컬 state는 새 계정용으로
비우고(`rm terraform.tfstate*` 또는 새 디렉터리) fresh apply 한다. `.tf` 코드는 git에 남아있다.
