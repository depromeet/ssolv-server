output "name_servers" {
  description = "가비아 네임서버 설정에 입력할 NS 레코드 4개"
  value       = aws_route53_zone.main.name_servers
}

output "zone_id" {
  value = aws_route53_zone.main.zone_id
}
