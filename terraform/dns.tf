# ============================================================================
# Route53: laimory.app 존 + 환경별 API 도메인 A 레코드
#   prod = laimory.app (apex), dev = dev.laimory.app — 기존 WAS EIP 직결.
#   apex 도 EIP(고정 IP) 대상이라 alias 없이 일반 A 레코드로 충분하다.
# 존 생성 후 output "route53_name_servers" 값을 가비아(도메인 레지스트라)에 NS 위임해야
# 동작한다. 존을 재생성하면 NS 4개가 바뀌므로 재위임도 다시 필요하다
# (절차는 README "도메인/TLS 적용 runbook" 참고).
# ============================================================================

resource "aws_route53_zone" "main" {
  name = "laimory.app"
}

resource "aws_route53_record" "api" {
  for_each = toset(var.environments)

  zone_id = aws_route53_zone.main.zone_id
  name    = var.api_domains[each.key]
  type    = "A"
  ttl     = 300
  records = [aws_eip.was[each.key].public_ip]
}
