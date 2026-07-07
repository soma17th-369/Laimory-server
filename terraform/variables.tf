variable "region" {
  description = "AWS 리전 (서울 고정)"
  type        = string
  default     = "ap-northeast-2"
}

variable "aws_profile" {
  description = "새 Sandbox 계정용 AWS CLI 프로필명 (aws configure sso 등으로 생성)"
  type        = string
}

variable "project_name" {
  description = "리소스 네이밍 prefix"
  type        = string
  default     = "laimory"
}

# ---------- 네트워크 ----------

variable "vpc_cidr" {
  description = "전용 VPC CIDR"
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "사용할 가용영역 2개 (퍼블릭/프라이빗 서브넷 배치)"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "public_subnet_cidrs" {
  description = "퍼블릭 서브넷 CIDR (WAS용, azs 순서 대응)"
  type        = list(string)
  default     = ["10.0.0.0/20", "10.0.16.0/20"]
}

variable "private_subnet_cidrs" {
  description = "프라이빗 서브넷 CIDR (데이터/AI용, azs 순서 대응)"
  type        = list(string)
  default     = ["10.0.32.0/20", "10.0.48.0/20"]
}

# ---------- 컴퓨트 ----------

variable "environments" {
  description = "WAS 인스턴스를 만들 환경 목록"
  type        = list(string)
  default     = ["dev", "prod"]
}

variable "was_instance_types" {
  description = "환경별 WAS 인스턴스 타입"
  type        = map(string)
  default = {
    dev  = "t3.small"
    prod = "t3.micro"
  }
}

variable "mysql_instance_type" {
  type    = string
  default = "t3.micro"
}

variable "redis_instance_type" {
  type    = string
  default = "t3.micro"
}

variable "ai_instance_type" {
  type    = string
  default = "t3.micro"
}

variable "mysql_private_ip" {
  description = "환경별 MySQL 박스 고정 사설 IP (private_subnet_cidrs[0] 범위 내)"
  type        = map(string)
  default = {
    dev  = "10.0.32.12"
    prod = "10.0.32.10"
  }
}

variable "redis_private_ip" {
  description = "Redis 박스 고정 사설 IP (private_subnet_cidrs[0] 범위 내)"
  type        = string
  default     = "10.0.32.11"
}

variable "root_volume_gib" {
  description = "EC2 루트 볼륨 크기(GiB)"
  type        = number
  default     = 20
}

# ---------- 도메인 / TLS ----------

variable "api_domains" {
  description = "환경별 API 도메인 (Route53 A 레코드 + nginx server_name + certbot 발급 대상)"
  type        = map(string)
  default = {
    dev  = "dev.laimory.app"
    prod = "laimory.app"
  }
}

variable "certbot_email" {
  description = "Let's Encrypt(certbot) 만료 알림 이메일"
  type        = string
}

# ---------- 애플리케이션 ----------

variable "app_port" {
  description = "Spring Boot 앱 포트 (--network host)"
  type        = number
  default     = 8080
}

# ---------- GitHub Actions OIDC ----------

variable "github_repo" {
  description = "OIDC 신뢰 대상 GitHub repo (owner/name)"
  type        = string
  default     = "soma17th-369/Laimory-server"
}

variable "github_deploy_branch" {
  description = "배포를 트리거하는 브랜치 (dev). prod 배포는 후속."
  type        = string
  default     = "dev"
}

# ---------- 비밀값 (secrets.auto.tfvars 로 주입, gitignore) ----------

variable "db_app_username" {
  description = "앱이 사용하는 MySQL 유저명"
  type        = string
  default     = "laimory"
}

variable "db_app_password" {
  description = "앱 MySQL 유저 비밀번호"
  type        = string
  sensitive   = true
  # user_data 의 SQL(IDENTIFIED BY '...') 파싱을 깨지 않도록 안전 문자셋만 허용.
  validation {
    condition     = can(regex("^[A-Za-z0-9!#%^*_+=:.,~@-]{8,128}$", var.db_app_password))
    error_message = "db_app_password: 8~128자, 영숫자+안전기호(! # % ^ * _ + = : . , ~ @ -)만 허용(따옴표·공백·$·백슬래시 등 금지)."
  }
}
# MySQL root 는 Ubuntu 기본 auth_socket(로컬 소켓 인증)을 유지한다.
# 앱은 root 를 쓰지 않고 db_app_username 으로 네트워크 접속하므로 root 비밀번호는 두지 않는다.

variable "redis_app_username" {
  description = "앱이 사용하는 Redis ACL 유저명"
  type        = string
  default     = "laimory_app"
}

variable "redis_app_password" {
  description = "Redis 앱 유저 비밀번호(AUTH)"
  type        = string
  sensitive   = true
  # user_data 의 Redis ACL(user ... >password) 파싱을 깨지 않도록 안전 문자셋만 허용.
  validation {
    condition     = can(regex("^[A-Za-z0-9!#%^*_+=:.,~@-]{8,128}$", var.redis_app_password))
    error_message = "redis_app_password: 8~128자, 영숫자+안전기호(! # % ^ * _ + = : . , ~ @ -)만 허용(따옴표·공백·$·백슬래시 등 금지)."
  }
}
