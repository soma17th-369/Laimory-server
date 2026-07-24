# ============================================================================
# IAM — EC2 인스턴스 role/profile + GitHub Actions OIDC role/provider
#   인스턴스 role: SSM코어 + ECR읽기 + scoped 인라인(photos put/delete + 백업 put)
#                  (기존의 AmazonS3FullAccess 는 제거)
#   gha role:      OIDC(dev 브랜치), ECR push + SSM SendCommand(새 dev WAS) + SSM read
# ============================================================================

# ---------- EC2 인스턴스 role ----------

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${var.project_name}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ec2_ecr_read" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

data "aws_iam_policy_document" "ec2_inline" {
  statement {
    sid       = "LaimoryPhotosWrite"
    effect    = "Allow"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.photos.arn}/*"]
  }

  statement {
    sid       = "LaimoryDbBinlogBackupWrite"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.backup.arn}/binlog/*"]
  }

  # MySQL 박스가 부팅 시 bootstrap/schema.sql 을 내려받아 적용(ddl-auto=validate 선적용).
  statement {
    sid       = "LaimoryBootstrapRead"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.backup.arn}/bootstrap/*"]
  }
}

resource "aws_iam_role_policy" "ec2_inline" {
  name   = "${var.project_name}-ec2-s3"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.ec2_inline.json
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project_name}-ec2-role"
  role = aws_iam_role.ec2.name
}

# ---------- monitoring 전용 최소 권한 role ----------
# 공용 EC2 role의 photos write/delete, backup write, ECR read를 상속하지 않는다.

resource "aws_iam_role" "monitoring" {
  name               = "${var.project_name}-monitoring-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "monitoring_ssm" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "monitoring_bootstrap_read" {
  statement {
    sid       = "MonitoringBootstrapRead"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.backup.arn}/bootstrap/monitoring/*"]
  }
}

resource "aws_iam_role_policy" "monitoring_bootstrap_read" {
  name   = "${var.project_name}-monitoring-bootstrap-read"
  role   = aws_iam_role.monitoring.id
  policy = data.aws_iam_policy_document.monitoring_bootstrap_read.json
}

data "aws_iam_policy_document" "monitoring_cloudwatch_read" {
  statement {
    sid       = "MonitoringCloudWatchRead"
    effect    = "Allow"
    actions   = ["cloudwatch:GetMetricData"]
    resources = ["*"]
  }

  statement {
    sid       = "MonitoringDescribeOwnInstance"
    effect    = "Allow"
    actions   = ["ec2:DescribeInstances"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "monitoring_cloudwatch_read" {
  name   = "${var.project_name}-monitoring-cloudwatch-read"
  role   = aws_iam_role.monitoring.id
  policy = data.aws_iam_policy_document.monitoring_cloudwatch_read.json
}

resource "aws_iam_instance_profile" "monitoring" {
  name = "${var.project_name}-monitoring-role"
  role = aws_iam_role.monitoring.name
}

# ---------- GitHub Actions OIDC ----------

data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
}

data "aws_iam_policy_document" "gha_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/${var.github_deploy_branch}"]
    }
  }
}

resource "aws_iam_role" "gha_deploy" {
  name               = "github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.gha_assume.json
}

data "aws_iam_policy_document" "gha_deploy" {
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPush"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.laimory.arn]
  }

  # dev WAS 인스턴스로만 배포 명령. prod 배포는 후속(트러스트/타깃 확장 필요).
  statement {
    sid     = "SsmSend"
    effect  = "Allow"
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.was["dev"].arn,
      "arn:aws:ssm:${local.region}::document/AWS-RunShellScript",
    ]
  }

  statement {
    sid       = "SsmRead"
    effect    = "Allow"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "gha_deploy" {
  name   = "laimory-deploy-permissions"
  role   = aws_iam_role.gha_deploy.id
  policy = data.aws_iam_policy_document.gha_deploy.json
}
