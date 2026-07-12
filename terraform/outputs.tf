# apply 후 deploy.yml / 앱 .env / 컷오버에 쓰는 값들.
#   terraform output          로 전체 확인
#   terraform output -json    로 스크립트 연동

output "account_id" {
  value = local.account_id
}

output "region" {
  value = var.region
}

output "was_instance_ids" {
  description = "환경별 WAS 인스턴스 ID (deploy.yml INSTANCE_ID)"
  value       = { for k, v in aws_instance.was : k => v.id }
}

output "was_public_ips" {
  description = "환경별 WAS EIP (고정 공인 IP)"
  value       = { for k, v in aws_eip.was : k => v.public_ip }
}

output "mysql_private_ips" {
  description = "환경별 MySQL 사설 IP"
  value       = { for k, v in aws_instance.mysql : k => v.private_ip }
}

output "redis_private_ip" {
  value = aws_instance.redis.private_ip
}

output "ai_instance_id" {
  value = aws_instance.ai.id
}

output "elk_instance_id" {
  description = "ELK(로그 수집) 인스턴스 ID — stop/start·SSM 포트포워딩 대상"
  value       = aws_instance.elk.id
}

output "elk_private_ip" {
  value = aws_instance.elk.private_ip
}

output "api_domains" {
  description = "환경별 API 도메인 (nginx server_name·certbot 발급 대상). DNS는 외부(가비아) 관리."
  value       = var.api_domains
}

output "cloudfront_domain" {
  description = "PHOTO_CDN_DOMAIN"
  value       = aws_cloudfront_distribution.photos.domain_name
}

output "photos_bucket" {
  description = "PHOTO_S3_BUCKET"
  value       = aws_s3_bucket.photos.bucket
}

output "backup_bucket" {
  value = aws_s3_bucket.backup.bucket
}

output "ecr_repository_url" {
  value = aws_ecr_repository.laimory.repository_url
}

output "gha_deploy_role_arn" {
  description = "deploy.yml role-to-assume (GitHub repo Variable AWS_DEPLOY_ROLE_ARN 로 설정)"
  value       = aws_iam_role.gha_deploy.arn
}

output "github_oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.github.arn
}

# deploy.yml / GitHub repo Variables 세팅용 요약
output "deploy_values" {
  value = {
    dev_instance_id = aws_instance.was["dev"].id
    role_to_assume  = aws_iam_role.gha_deploy.arn
    ecr_registry    = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
    ecr_repository  = aws_ecr_repository.laimory.name
    region          = var.region
  }
}
