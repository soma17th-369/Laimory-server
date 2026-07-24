# ============================================================================
# S3(photos + binlog 백업) + OAC + CloudFront(무서명) + ECR
# 현 계정 실측값 재현: SSE AES256, 퍼블릭 완전차단, OAC read 정책,
# PriceClass_200, Managed-CachingOptimized, edge function 없음.
# ============================================================================

# ---------- 사진 버킷 ----------

resource "aws_s3_bucket" "photos" {
  bucket = local.photos_bucket
  tags   = { Name = local.photos_bucket }
}

resource "aws_s3_bucket_public_access_block" "photos" {
  bucket                  = aws_s3_bucket.photos.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "photos_oac" {
  statement {
    sid    = "AllowCloudFrontOACRead"
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.photos.arn}/*"]
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.photos.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "photos" {
  bucket = aws_s3_bucket.photos.id
  policy = data.aws_iam_policy_document.photos_oac.json
}

# ---------- binlog 백업 버킷 ----------

resource "aws_s3_bucket" "backup" {
  bucket = local.backup_bucket
  tags   = { Name = local.backup_bucket }
}

resource "aws_s3_bucket_public_access_block" "backup" {
  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# MySQL 박스가 부팅 시 내려받아 적용할 스키마 (ddl-auto=validate 선적용용)
resource "aws_s3_object" "schema" {
  bucket = aws_s3_bucket.backup.id
  key    = "bootstrap/schema.sql"
  source = "${path.module}/../src/main/resources/db/schema.sql"
  etag   = filemd5("${path.module}/../src/main/resources/db/schema.sql")
}

# ELK 로그 수집 부트스트랩. ELK 박스가 compose/ILM/템플릿을, WAS 박스가 filebeat.yml 을 pull 한다.
# (IAM 인라인 LaimoryBootstrapRead 가 bootstrap/* GetObject 허용 — 별도 IAM 변경 불필요.)
resource "aws_s3_object" "elk_compose" {
  bucket = aws_s3_bucket.backup.id
  key    = "bootstrap/elk/docker-compose.yml"
  source = "${path.module}/../deploy/elk/docker-compose.yml"
  etag   = filemd5("${path.module}/../deploy/elk/docker-compose.yml")
}

resource "aws_s3_object" "elk_ilm" {
  bucket = aws_s3_bucket.backup.id
  key    = "bootstrap/elk/ilm-policy.json"
  source = "${path.module}/../deploy/elk/ilm-policy.json"
  etag   = filemd5("${path.module}/../deploy/elk/ilm-policy.json")
}

resource "aws_s3_object" "elk_template" {
  bucket = aws_s3_bucket.backup.id
  key    = "bootstrap/elk/index-template.json"
  source = "${path.module}/../deploy/elk/index-template.json"
  etag   = filemd5("${path.module}/../deploy/elk/index-template.json")
}

resource "aws_s3_object" "elk_filebeat" {
  bucket = aws_s3_bucket.backup.id
  key    = "bootstrap/elk/filebeat.yml"
  source = "${path.module}/../deploy/elk/filebeat.yml"
  etag   = filemd5("${path.module}/../deploy/elk/filebeat.yml")
}

# Prometheus/Grafana monitoring 부트스트랩. 이 prefix에는 비밀을 넣지 않는다. monitoring 전용 IAM은
# 이 prefix의 GetObject만 허용하고 ListBucket, backup write, photos 권한은 갖지 않는다.
locals {
  monitoring_bootstrap_assets = {
    "docker-compose.yml"                                               = "docker-compose.yml"
    "prometheus/prometheus.yml"                                        = "prometheus/prometheus.yml"
    "blackbox/blackbox.yml"                                            = "blackbox/blackbox.yml"
    "node-exporter/install.sh"                                         = "node-exporter/install.sh"
    "node-exporter/uninstall.sh"                                       = "node-exporter/uninstall.sh"
    "grafana/provisioning/datasources/prometheus.yml"                  = "grafana/provisioning/datasources/prometheus.yml"
    "grafana/provisioning/datasources/elasticsearch.yml"               = "grafana/provisioning/datasources/elasticsearch.yml"
    "grafana/provisioning/dashboards/provider.yml"                     = "grafana/provisioning/dashboards/provider.yml"
    "grafana/provisioning/dashboards/json/laimory-overview.json"       = "grafana/provisioning/dashboards/json/laimory-overview.json"
    "grafana/provisioning/dashboards/json/laimory-jvm-spring.json"     = "grafana/provisioning/dashboards/json/laimory-jvm-spring.json"
    "grafana/provisioning/dashboards/json/laimory-infrastructure.json" = "grafana/provisioning/dashboards/json/laimory-infrastructure.json"
    "grafana/provisioning/dashboards/json/laimory-logs.json"           = "grafana/provisioning/dashboards/json/laimory-logs.json"
    "grafana/provisioning/alerting/contact-points.yml"                 = "grafana/provisioning/alerting/contact-points.yml"
    "grafana/provisioning/alerting/notification-policy.yml"            = "grafana/provisioning/alerting/notification-policy.yml"
    "grafana/provisioning/alerting/rules.yml"                          = "grafana/provisioning/alerting/rules.yml"
    "grafana/provisioning/alerting/operational-rules.yml"              = "grafana/provisioning/alerting/operational-rules.yml"
    "grafana/provisioning/alerting/templates.yml"                      = "grafana/provisioning/alerting/templates.yml"
    "grafana/smoke/smoke-rule.firing.yml"                              = "grafana/smoke/smoke-rule.firing.yml"
    "grafana/smoke/smoke-rule.resolved.yml"                            = "grafana/smoke/smoke-rule.resolved.yml"
    "grafana/smoke/smoke-rule.delete.yml"                              = "grafana/smoke/smoke-rule.delete.yml"
    "scripts/install-secret.sh"                                        = "scripts/install-secret.sh"
    "scripts/validate-secrets.sh"                                      = "scripts/validate-secrets.sh"
    "scripts/configure-mysql-exporter-user.sh"                         = "scripts/configure-mysql-exporter-user.sh"
    "scripts/configure-redis-exporter-user.sh"                         = "scripts/configure-redis-exporter-user.sh"
    "scripts/collect-aws-metrics.sh"                                   = "scripts/collect-aws-metrics.sh"
    "scripts/collect-elasticsearch-metrics.sh"                         = "scripts/collect-elasticsearch-metrics.sh"
    "scripts/collect-filebeat-metrics.sh"                              = "scripts/collect-filebeat-metrics.sh"
    "nginx/manage-grafana-proxy.sh"                                    = "nginx/manage-grafana-proxy.sh"
    "systemd/laimory-monitoring.service"                               = "systemd/laimory-monitoring.service"
    "systemd/laimory-aws-metrics.service"                              = "systemd/laimory-aws-metrics.service"
    "systemd/laimory-aws-metrics.timer"                                = "systemd/laimory-aws-metrics.timer"
    "systemd/laimory-elasticsearch-metrics.service"                    = "systemd/laimory-elasticsearch-metrics.service"
    "systemd/laimory-elasticsearch-metrics.timer"                      = "systemd/laimory-elasticsearch-metrics.timer"
    "systemd/laimory-filebeat-metrics.service"                         = "systemd/laimory-filebeat-metrics.service"
    "systemd/laimory-filebeat-metrics.timer"                           = "systemd/laimory-filebeat-metrics.timer"
  }

  monitoring_application_targets = templatefile("${path.module}/../deploy/monitoring/prometheus/application-targets.yml.tftpl", {
    dev_was_private_ip = aws_instance.was["dev"].private_ip
  })

  monitoring_node_targets = templatefile("${path.module}/../deploy/monitoring/prometheus/node-targets.yml.tftpl", {
    monitoring_private_ip = var.monitoring_private_ip
    dev_was_private_ip    = aws_instance.was["dev"].private_ip
    dev_mysql_private_ip  = var.mysql_private_ip["dev"]
    redis_private_ip      = var.redis_private_ip
    elk_private_ip        = var.elk_private_ip
  })

  monitoring_probe_targets = templatefile("${path.module}/../deploy/monitoring/prometheus/probe-targets.yml.tftpl", {
    dev_api_domain = var.api_domains["dev"]
  })
}

resource "aws_s3_object" "monitoring_assets" {
  for_each = local.monitoring_bootstrap_assets

  bucket = aws_s3_bucket.backup.id
  key    = "bootstrap/monitoring/${each.key}"
  source = "${path.module}/../deploy/monitoring/${each.value}"
  etag   = filemd5("${path.module}/../deploy/monitoring/${each.value}")
}

resource "aws_s3_object" "monitoring_application_targets" {
  bucket       = aws_s3_bucket.backup.id
  key          = "bootstrap/monitoring/prometheus/targets/application.yml"
  content      = local.monitoring_application_targets
  content_type = "application/yaml"
  etag         = md5(local.monitoring_application_targets)
}

resource "aws_s3_object" "monitoring_node_targets" {
  bucket       = aws_s3_bucket.backup.id
  key          = "bootstrap/monitoring/prometheus/targets/node.yml"
  content      = local.monitoring_node_targets
  content_type = "application/yaml"
  etag         = md5(local.monitoring_node_targets)
}

resource "aws_s3_object" "monitoring_probe_targets" {
  bucket       = aws_s3_bucket.backup.id
  key          = "bootstrap/monitoring/prometheus/targets/probe.yml"
  content      = local.monitoring_probe_targets
  content_type = "application/yaml"
  etag         = md5(local.monitoring_probe_targets)
}

# ---------- OAC + CloudFront (무서명 서빙) ----------

resource "aws_cloudfront_origin_access_control" "photos" {
  name                              = "${var.project_name}-photos-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "photos" {
  enabled     = true
  price_class = "PriceClass_200"
  comment     = "${var.project_name} photos (unsigned)"

  origin {
    domain_name              = aws_s3_bucket.photos.bucket_regional_domain_name
    origin_id                = "photos-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.photos.id
  }

  default_cache_behavior {
    target_origin_id       = "photos-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    # AWS Managed-CachingOptimized (전 계정 공통 ID)
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = { Name = "${var.project_name}-photos-cf" }
}

# ---------- ECR ----------

resource "aws_ecr_repository" "laimory" {
  name                 = "laimory"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "laimory" {
  repository = aws_ecr_repository.laimory.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 15개 이미지만 보존"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 15
      }
      action = { type = "expire" }
    }]
  })
}
