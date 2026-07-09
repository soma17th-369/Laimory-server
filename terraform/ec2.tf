# ============================================================================
# EC2 6대 — 최신 Ubuntu 24.04 base + user_data 스크립트 (커스텀 AMI 미사용)
#   was(dev/prod):   퍼블릭 서브넷 + EIP, docker + nginx(80→8080 프록시)
#   mysql(dev/prod): 프라이빗, env별 고정 IP, mysql8 + schema. binlog→S3 백업은 prod만
#   redis:           프라이빗, 고정 IP, redis7 + ACL (dev/prod 공유, key-prefix 격리)
#   ai:              프라이빗 (박스만; 앱 배포는 Laimory-AI 소관)
# 모든 인스턴스는 lifecycle{ ignore_changes=[ami] } — AMI(most_recent) 롤로 running 박스가
# 재생성되지 않게 고정한다(세팅은 user_data로 재현되는 cattle 인프라).
# ============================================================================

# ---------- WAS (dev/prod) ----------

resource "aws_instance" "was" {
  for_each = toset(var.environments)

  ami                  = local.ubuntu_ami
  instance_type        = var.was_instance_types[each.key]
  subnet_id            = aws_subnet.public[index(var.environments, each.key) % length(aws_subnet.public)].id
  iam_instance_profile = aws_iam_instance_profile.ec2.name

  # dev WAS 에만 bastion SG 추가 부착(읽기전용 DB 터널용 22번). prod 엔 붙이지 않는다.
  vpc_security_group_ids = each.key == "dev" ? [aws_security_group.was.id, aws_security_group.dev_bastion_ssh.id] : [aws_security_group.was.id]

  # 기존 dev/prod WAS는 nginx/certbot/.env를 SSM으로 수동 관리한다(user_data는 신규 박스 최초 부팅 재현용).
  # user_data_replace_on_change=false만으로는 부족하다 — 이건 "교체 대신 stop/start로 user_data를 갱신"하는
  # 옵션이라, user_data가 바뀌면 apply가 기존 박스를 stop/start(다운타임)한다. 그래서 아래 lifecycle에
  # user_data를 ignore_changes로 넣어 기존 박스엔 코드의 user_data 변경을 반영하지 않는다.
  user_data_replace_on_change = false

  user_data = templatefile("${path.module}/user_data/was.sh.tftpl", {
    env                    = each.key
    region                 = var.region
    db_host                = var.mysql_private_ip[each.key]
    db_username            = var.db_app_username
    db_password            = var.db_app_password
    redis_host             = var.redis_private_ip
    redis_username         = var.redis_app_username
    redis_password         = var.redis_app_password
    redis_ssl              = "false"
    photo_bucket           = aws_s3_bucket.photos.bucket
    photo_cdn_domain       = aws_cloudfront_distribution.photos.domain_name
    api_domain             = var.api_domains[each.key]
    certbot_email          = var.certbot_email
    bastion_ssh_public_key = var.bastion_ssh_public_key
  })

  root_block_device {
    volume_size = var.root_volume_gib
    volume_type = "gp3"
  }

  tags = { Name = "${var.project_name}-${each.key}-was-01" }

  lifecycle {
    # ami: most_recent 롤로 재생성 방지. user_data: 기존 박스 stop/start·drift 방지(위 주석) —
    # 신규 박스는 생성 시점 user_data로 부팅되므로 재현성은 유지된다.
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip" "was" {
  for_each = toset(var.environments)

  instance   = aws_instance.was[each.key].id
  domain     = "vpc"
  depends_on = [aws_internet_gateway.main]

  tags = { Name = "${var.project_name}-${each.key}-was-01-eip" }
}

# ---------- MySQL (프라이빗, 고정 IP) ----------

resource "aws_instance" "mysql" {
  for_each = toset(var.environments)

  ami                    = local.ubuntu_ami
  instance_type          = var.mysql_instance_type
  subnet_id              = aws_subnet.private[0].id
  private_ip             = var.mysql_private_ip[each.key]
  vpc_security_group_ids = [aws_security_group.db.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  # user_data 변경으로 인스턴스 재생성 방지 — 기존 prod 박스 보존(prod IP·subnet 불변)
  user_data_replace_on_change = false

  user_data = templatefile("${path.module}/user_data/mysql.sh.tftpl", {
    env               = each.key
    region            = var.region
    db_name           = "laimory"
    app_user          = var.db_app_username
    app_password      = var.db_app_password
    backup_bucket     = aws_s3_bucket.backup.bucket
    schema_s3_uri     = "s3://${aws_s3_bucket.backup.bucket}/bootstrap/schema.sql"
    backup_enabled    = each.key == "prod" # dev는 binlog→S3 백업 스킵(스키마는 부팅 시 S3서 재적용)
    readonly_password = var.db_readonly_password
  })

  root_block_device {
    volume_size = var.root_volume_gib
    volume_type = "gp3"
  }

  tags = { Name = "${var.project_name}-${each.key}-mysql-01" }

  lifecycle {
    # user_data: WAS 와 동일하게 기존 박스 drift 방지(신규 박스만 생성 시점 user_data로 재현).
    ignore_changes = [ami, user_data]
  }

  # NAT(apt)·프라이빗 라우팅·schema 업로드가 준비된 뒤 부팅되도록
  depends_on = [
    aws_nat_gateway.main,
    aws_route_table_association.private,
    aws_s3_object.schema,
  ]
}

# 기존 싱글턴 mysql(현 laimory-prod-mysql, 10.0.32.10)을 prod 키로 이관.
# → prod 박스는 보존(재생성 X, IP·subnet 불변), dev 박스만 신규 생성된다.
moved {
  from = aws_instance.mysql
  to   = aws_instance.mysql["prod"]
}

# ---------- Redis (프라이빗, 고정 IP) ----------

resource "aws_instance" "redis" {
  ami                    = local.ubuntu_ami
  instance_type          = var.redis_instance_type
  subnet_id              = aws_subnet.private[0].id
  private_ip             = var.redis_private_ip
  vpc_security_group_ids = [aws_security_group.redis.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  user_data = templatefile("${path.module}/user_data/redis.sh.tftpl", {
    redis_username = var.redis_app_username
    redis_password = var.redis_app_password
  })

  root_block_device {
    volume_size = var.root_volume_gib
    volume_type = "gp3"
  }

  tags = { Name = "${var.project_name}-redis-01" }

  lifecycle {
    ignore_changes = [ami]
  }

  depends_on = [
    aws_nat_gateway.main,
    aws_route_table_association.private,
  ]
}

# ---------- AI (프라이빗, 박스만) ----------

resource "aws_instance" "ai" {
  ami                    = local.ubuntu_ami
  instance_type          = var.ai_instance_type
  subnet_id              = aws_subnet.private[1 % length(aws_subnet.private)].id
  vpc_security_group_ids = [aws_security_group.ai.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  user_data = templatefile("${path.module}/user_data/ai.sh.tftpl", {})

  root_block_device {
    volume_size = var.root_volume_gib
    volume_type = "gp3"
  }

  tags = { Name = "${var.project_name}-dev-ai-01" }

  lifecycle {
    ignore_changes = [ami]
  }

  depends_on = [
    aws_nat_gateway.main,
    aws_route_table_association.private,
  ]
}
