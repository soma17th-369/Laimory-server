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
