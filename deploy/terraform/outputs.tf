output "instance_a_eip" {
  description = "가비아 api.ssolv.site A레코드에 등록"
  value       = module.compute.eip_a
}

output "instance_b_eip" {
  description = "가비아 registry.ssolv.site A레코드에 등록"
  value       = module.compute.eip_b
}

output "instance_b_private_ip" {
  description = "인스턴스 A의 .env에 INSTANCE_B_PRIVATE_IP 값으로 사용"
  value       = module.compute.instance_b_private_ip
}

output "rds_endpoint" {
  description = ".env의 PROD_DB_ENDPOINT 값으로 사용"
  value       = module.database.rds_endpoint
}

output "route53_name_servers" {
  description = "가비아 네임서버에 입력할 NS 레코드 4개"
  value       = module.dns.name_servers
}

output "next_steps" {
  value = <<-EOT
    ─── apply 완료 후 할 일 ───────────────────────────────────────────────
    1. 가비아 DNS 변경 (네임서버 위임)
       가비아 도메인 설정에서 네임서버 4개를 아래 값으로 변경:
       ${join("\n       ", module.dns.name_servers)}

       * 주의: A 레코드를 직접 수정하는 것이 아니라 NS 레코드를 위임해야 
         Route53의 헬스체크 기반 장애 조치가 정상 작동합니다.

    2. 인스턴스 A .env에 추가
       INSTANCE_B_PRIVATE_IP=${module.compute.instance_b_private_ip}
       PROD_DB_ENDPOINT=${module.database.rds_endpoint}

    3. 두 인스턴스 모두에 추가
       PROD_DB_ENDPOINT=${module.database.rds_endpoint}

    4. 배포 가이드 참고: .claude/infra/WORKFLOW.md Phase 3
    ─────────────────────────────────────────────────────────────────────
  EOT
}
