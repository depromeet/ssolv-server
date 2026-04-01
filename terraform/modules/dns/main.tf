resource "aws_route53_zone" "main" {
  name = var.domain

  tags = {
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── 헬스체크 ─────────────────────────────────────────────────────────────────

resource "aws_route53_health_check" "app_a" {
  count             = var.instance_a_ip != null ? 1 : 0
  ip_address        = var.instance_a_ip
  port              = 443
  type              = "HTTPS"
  resource_path     = "/actuator/health"
  failure_threshold = 3
  request_interval  = 30

  tags = {
    Name      = "${var.project}-hc-a"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_route53_health_check" "app_b" {
  ip_address        = var.instance_b_ip
  port              = 443
  type              = "HTTPS"
  resource_path     = "/actuator/health"
  failure_threshold = 3
  request_interval  = 30

  tags = {
    Name      = "${var.project}-hc-b"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── api.ssolv.site — Multivalue Answer (헬스체크 연동) ──────────────────────

resource "aws_route53_record" "api_a" {
  count                            = var.instance_a_ip != null ? 1 : 0
  zone_id                          = aws_route53_zone.main.zone_id
  name                             = "api.${var.domain}"
  type                             = "A"
  ttl                              = 60
  set_identifier                   = "instance-a"
  health_check_id                  = aws_route53_health_check.app_a[0].id
  records                          = [var.instance_a_ip]
  multivalue_answer_routing_policy = true
}

resource "aws_route53_record" "api_b" {
  zone_id                          = aws_route53_zone.main.zone_id
  name                             = "api.${var.domain}"
  type                             = "A"
  ttl                              = 60
  set_identifier                   = "instance-b"
  health_check_id                  = aws_route53_health_check.app_b.id
  records                          = [var.instance_b_ip]
  multivalue_answer_routing_policy = true
}

# ─── registry.ssolv.site — B 고정 (registry는 단일) ──────────────────────────

resource "aws_route53_record" "registry" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "registry.${var.domain}"
  type    = "A"
  ttl     = 300
  records = [var.instance_b_ip]
}

# ─── Vercel Frontend (ssolv.site / www.ssolv.site) ───────────────────────────

# 루트 도메인 (ssolv.site) — Vercel Anycast IP로 연결
resource "aws_route53_record" "apex" {
  zone_id = aws_route53_zone.main.zone_id
  name    = var.domain
  type    = "A"
  ttl     = 300
  records = ["76.76.21.21"]
}

# WWW 도메인 (www.ssolv.site) — Vercel DNS로 연결
resource "aws_route53_record" "www" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "www.${var.domain}"
  type    = "CNAME"
  ttl     = 300
  records = ["cname.vercel-dns.com."]
}
