data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

# 최신 Ubuntu 24.04 LTS (noble) AMI — Canonical 공식(owner 099720109477).
# 커스텀 AMI 대신 최신 base 이미지를 쓰고 세팅은 전부 user_data로 스크립트화한다.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name

  # 버킷명은 계정ID를 포함하므로 새 계정에선 자동으로 새 이름이 된다.
  photos_bucket = "${var.project_name}-photos-${local.account_id}-${local.region}"
  backup_bucket = "${var.project_name}-db-binlog-${local.account_id}-${local.region}"

  ubuntu_ami = data.aws_ami.ubuntu.id
}
