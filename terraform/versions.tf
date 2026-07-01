terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # 로컬 backend: Innovation Sandbox 계정은 리스 만료 시 nuke되므로
  # 원격 state를 sandbox 안에 두지 않는다. state 파일에는 비밀이 포함될 수 있어
  # 반드시 .gitignore 로 커밋을 막는다(이 디렉터리의 .gitignore 참고).
  backend "local" {}
}
