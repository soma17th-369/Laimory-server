# ============================================================================
# 보안그룹 — SG는 stateful·inbound 기본 차단이므로 같은 VPC라도 명시적으로 연다.
#   was:   80/443 ← world, 8080 ← ai (콜백)
#   db:    3306 ← was, ai (dev-ai 직접 접근)
#   redis: 6379 ← was
#   ai:    inbound 없음 (egress only)
# ============================================================================

resource "aws_security_group" "was" {
  name        = "${var.project_name}-was-sg"
  description = "WAS: public 80/443, app-port callback from AI"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.project_name}-was-sg" }
}

resource "aws_security_group" "db" {
  name        = "${var.project_name}-db-sg"
  description = "MySQL 3306 from WAS"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.project_name}-db-sg" }
}

resource "aws_security_group" "redis" {
  name        = "${var.project_name}-redis-sg"
  description = "Redis 6379 from WAS"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.project_name}-redis-sg" }
}

resource "aws_security_group" "ai" {
  name        = "${var.project_name}-ai-sg"
  description = "AI: egress only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.project_name}-ai-sg" }
}

# ---------- WAS inbound ----------

resource "aws_vpc_security_group_ingress_rule" "was_http" {
  security_group_id = aws_security_group.was.id
  description       = "HTTP"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "was_https" {
  security_group_id = aws_security_group.was.id
  description       = "HTTPS"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "was_callback" {
  security_group_id            = aws_security_group.was.id
  description                  = "AI to app callback (server-to-server)"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port
  to_port                      = var.app_port
  referenced_security_group_id = aws_security_group.ai.id
}

# ---------- DB inbound ----------

resource "aws_vpc_security_group_ingress_rule" "db_mysql" {
  security_group_id            = aws_security_group.db.id
  description                  = "MySQL from WAS"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.was.id
}

resource "aws_vpc_security_group_ingress_rule" "db_mysql_ai" {
  security_group_id            = aws_security_group.db.id
  description                  = "MySQL from AI (dev-ai direct DB access)"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.ai.id
}

# ---------- Redis inbound ----------

resource "aws_vpc_security_group_ingress_rule" "redis_6379" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from WAS"
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.was.id
}

# ---------- egress: 전체 허용 (SSM/ECR/apt/S3 등 outbound) ----------

resource "aws_vpc_security_group_egress_rule" "was_all" {
  security_group_id = aws_security_group.was.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "db_all" {
  security_group_id = aws_security_group.db.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "redis_all" {
  security_group_id = aws_security_group.redis.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "ai_all" {
  security_group_id = aws_security_group.ai.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------- dev bastion SSH (읽기전용 DB 터널 전용) ----------
# dev WAS 에만 부착하는 별도 SG. dev-mysql 을 읽기전용 열람하려는 사용자가 SSH 포트포워딩으로
# 접근하도록 22번을 allowlist IP 에만 연다. WAS SG(aws_security_group.was)는 dev·prod 공유라
# 거기에 22를 열면 prod 까지 노출되므로, dev 전용 SG 로 분리해 dev WAS 에만 붙인다(ec2.tf).

resource "aws_security_group" "dev_bastion_ssh" {
  name        = "${var.project_name}-dev-bastion-ssh-sg"
  description = "dev WAS SSH for read-only DB tunnel (allowlisted IPs)"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.project_name}-dev-bastion-ssh-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "dev_bastion_ssh" {
  for_each = toset(var.bastion_ssh_allowed_cidrs)

  security_group_id = aws_security_group.dev_bastion_ssh.id
  description       = "SSH tunnel for dev read-only DB viewer"
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_egress_rule" "dev_bastion_ssh_all" {
  security_group_id = aws_security_group.dev_bastion_ssh.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------- ELK (로그 수집, 사설) ----------
# 9200(ES)·5601(Kibana) 둘 다 WAS SG 에서만 → ELK 박스 공개 노출 0.
#   9200 ← WAS(Filebeat 전송), 5601 ← WAS(dev-was nginx /kibana 프록시). 외부 노출은 nginx 443 에서 종단.
# WAS SG 는 이미 all-egress 라 WAS→ELK 는 별도 규칙 불필요. egress 는 이미지 pull(NAT)용 전체 허용.

resource "aws_security_group" "elk" {
  name        = "${var.project_name}-elk-sg"
  description = "ELK: ES 9200 + Kibana 5601 from WAS only (private)"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.project_name}-elk-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "elk_es" {
  security_group_id            = aws_security_group.elk.id
  description                  = "Elasticsearch from WAS (Filebeat)"
  ip_protocol                  = "tcp"
  from_port                    = 9200
  to_port                      = 9200
  referenced_security_group_id = aws_security_group.was.id
}

resource "aws_vpc_security_group_ingress_rule" "elk_kibana" {
  security_group_id            = aws_security_group.elk.id
  description                  = "Kibana from WAS (dev-was nginx reverse proxy)"
  ip_protocol                  = "tcp"
  from_port                    = 5601
  to_port                      = 5601
  referenced_security_group_id = aws_security_group.was.id
}

resource "aws_vpc_security_group_egress_rule" "elk_all" {
  security_group_id = aws_security_group.elk.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
