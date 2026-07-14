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

# ---------- dev DB bastion (읽기전용 열람 접근) ----------
# dev-mysql 을 DataGrip 등으로 읽기전용 열람하려는 사용자(예: 클라 개발자)가 dev WAS 를 SSH
# 포트포워딩 관문으로 삼아 접근하는 경로. 아래 3개 값으로 SG allowlist·터널 유저 공개키·readonly
# DB 비밀번호를 주입한다. 앞의 둘은 비밀이 아니라 terraform.tfvars 에, 비밀번호만 secrets 에 둔다.

variable "bastion_ssh_allowed_cidrs" {
  description = "dev WAS 22번(SSH 터널) 접근 허용 CIDR — dev-mysql 읽기전용 열람자 공인 IP. 비밀 아님."
  type        = list(string)
  default     = []
}

variable "bastion_ssh_public_key" {
  description = "dev-mysql 읽기전용 터널용 dbviewer 공개키(포워딩 전용·nologin). 공개키라 비밀 아님."
  type        = string
  default     = ""
}

variable "db_readonly_password" {
  description = "dev-mysql readonly 계정(SELECT ON laimory.*) 비밀번호"
  type        = string
  sensitive   = true
  # user_data 의 SQL(IDENTIFIED BY '...') 파싱을 깨지 않도록 안전 문자셋만 허용(db_app_password 와 동일 규칙).
  validation {
    condition     = can(regex("^[A-Za-z0-9!#%^*_+=:.,~@-]{8,128}$", var.db_readonly_password))
    error_message = "db_readonly_password: 8~128자, 영숫자+안전기호(! # % ^ * _ + = : . , ~ @ -)만 허용(따옴표·공백·$·백슬래시 등 금지)."
  }
}

# ---------- ELK 로그 수집 (dev) ----------
# 단일 ELK 박스(사설, stop/start 운용)에 ES+Kibana. Filebeat 는 WAS 박스에서 돈다.
# dev/prod 는 environment 필드 + 인덱스명(laimory-dev-*)으로 분리(박스는 공유 예정, 지금은 dev만).

variable "elk_instance_type" {
  type    = string
  default = "t3.medium"
}

variable "elk_private_ip" {
  description = "ELK 박스 고정 사설 IP (private_subnet_cidrs[0] 범위 내 여유 IP)"
  type        = string
  default     = "10.0.32.13"
}

variable "elk_root_volume_gib" {
  description = "ELK 루트 볼륨 크기(GiB) — ES 데이터용, 공유 root_volume_gib(20) 오버라이드"
  type        = number
  default     = 30
}

variable "elk_stack_version" {
  description = "Elastic 스택 태그(ES=Kibana=Filebeat 동일). 8.19 라인 고정 — 9.x 는 filebeat container input 미지원. apply 직전 최신 8.19 패치 재확인."
  type        = string
  default     = "8.19.18"
}

variable "elk_es_java_opts" {
  description = "Elasticsearch 힙(medium=1536m). OOM 시 낮추거나 향후 small=512m."
  type        = string
  default     = "-Xms1536m -Xmx1536m"
}

variable "elk_elastic_password" {
  description = "Elasticsearch elastic superuser 비밀번호 — 첫 부팅 시 빈 볼륨에 각인(이후 .env 수정 무효, change-password API 또는 볼륨 wipe 로만 변경)"
  type        = string
  sensitive   = true
  # .env/compose 변수전개·URL basic-auth 파싱을 깨지 않도록 안전 문자셋만 허용(db_app_password 와 동일 규칙).
  validation {
    condition     = can(regex("^[A-Za-z0-9!#%^*_+=:.,~@-]{8,128}$", var.elk_elastic_password))
    error_message = "elk_elastic_password: 8~128자, 영숫자+안전기호(! # % ^ * _ + = : . , ~ @ -)만 허용(따옴표·공백·$·백슬래시 등 금지)."
  }
}

variable "elk_kibana_password" {
  description = "Kibana↔ES 연결용 kibana_system 계정 비밀번호(setup 이 매 up 마다 재적용이라 안전)"
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^[A-Za-z0-9!#%^*_+=:.,~@-]{8,128}$", var.elk_kibana_password))
    error_message = "elk_kibana_password: 8~128자, 영숫자+안전기호(! # % ^ * _ + = : . , ~ @ -)만 허용(따옴표·공백·$·백슬래시 등 금지)."
  }
}

variable "elk_filebeat_password" {
  description = "Filebeat 전송용 filebeat_writer 계정 비밀번호(WAS Filebeat 컨테이너에 -e 로 주입)"
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^[A-Za-z0-9!#%^*_+=:.,~@-]{8,128}$", var.elk_filebeat_password))
    error_message = "elk_filebeat_password: 8~128자, 영숫자+안전기호(! # % ^ * _ + = : . , ~ @ -)만 허용(따옴표·공백·$·백슬래시 등 금지)."
  }
}
