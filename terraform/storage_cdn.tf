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
