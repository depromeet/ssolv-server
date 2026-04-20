output "instance_a_id" {
  value = var.app_instance_count >= 2 ? aws_instance.app_a[0].id : null
}

output "instance_b_id" {
  value = aws_instance.app_b.id
}

output "instance_b_private_ip" {
  description = "인스턴스 A의 REDIS_HOST, OTLP_ENDPOINT, nginx upstream에 사용"
  value       = aws_instance.app_b.private_ip
}

output "eip_a" {
  description = "가비아 api.ssolv.site A레코드에 등록할 IP"
  value       = var.app_instance_count >= 2 ? aws_eip.app_a[0].public_ip : null
}

output "eip_b" {
  description = "가비아 registry.ssolv.site A레코드에 등록할 IP"
  value       = aws_eip.app_b.public_ip
}
