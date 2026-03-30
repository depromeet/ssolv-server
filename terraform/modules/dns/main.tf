resource "aws_route53_zone" "main" {
  name = var.domain

  tags = {
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── 헬스체크 ─────────────────────────────────────────────────────────────────

resource "aws_route53_health_check" "app_a" {
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
  zone_id                          = aws_route53_zone.main.zone_id
  name                             = "api.${var.domain}"
  type                             = "A"
  ttl                              = 60
  set_identifier                   = "instance-a"
  health_check_id                  = aws_route53_health_check.app_a.id
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

# ─── www.ssolv.site → ssolv.site (프론트엔드 대비) ───────────────────────────
# 현재는 API 서버만 있으므로 주석 처리
# resource "aws_route53_record" "www" { ... }
