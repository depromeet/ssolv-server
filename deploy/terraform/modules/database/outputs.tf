output "rds_endpoint" {
  description = "RDS 엔드포인트 — .env의 PROD_DB_ENDPOINT에 사용"
  value       = aws_db_instance.main.address
}

output "rds_port" {
  value = aws_db_instance.main.port
}
