variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project" {
  type    = string
  default = "ssolv"
}

variable "vpc_cidr" {
  type    = string
  default = "10.1.0.0/16"
}

variable "ami_id" {
  description = "Ubuntu 22.04 LTS x86_64 (ap-northeast-2)"
  type        = string
}

variable "instance_type_a" {
  description = "인스턴스 A (nginx + app-server) 인스턴스 타입"
  type        = string
  default     = "t3.micro"
}

variable "instance_type_b" {
  description = "인스턴스 B (app-server + infra) 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "key_name" {
  description = "AWS EC2 키페어 이름"
  type        = string
}

variable "public_key" {
  description = "depromeet-secret.pem 에서 추출한 SSH 공개키"
  type        = string
  sensitive   = true
}

variable "app_instance_count" {
  description = "앱 서버 인스턴스 수 (1=단일 B만, 2=A+B 멀티서버)"
  type        = number
  default     = 2
}

variable "db_name" {
  type    = string
  default = "ssolv"
}

variable "db_username" {
  type    = string
  default = "ssolv"
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "db_instance_class" {
  type    = string
  default = "db.t3.micro"
}

variable "domain" {
  description = "루트 도메인"
  type        = string
  default     = "ssolv.site"
}

variable "restaurant_import_bucket_name" {
  description = "식당 원천 파일 저장용 S3 버킷 이름. 전역 유니크해야 함."
  type        = string
}

variable "restaurant_import_raw_expiration_days" {
  description = "raw/ 하위 월간 원천 파일 보관 기간"
  type        = number
  default     = 365
}

variable "restaurant_import_reports_expiration_days" {
  description = "reports/ 하위 배치 결과 파일 보관 기간"
  type        = number
  default     = 180
}

variable "restaurant_batch_container_image" {
  description = "ECS Fargate에서 실행할 ssolv-batch 컨테이너 이미지 URI"
  type        = string
}

variable "restaurant_batch_cpu" {
  description = "식당 배치 Fargate Task CPU unit"
  type        = number
  default     = 1024
}

variable "restaurant_batch_memory" {
  description = "식당 배치 Fargate Task memory MiB"
  type        = number
  default     = 2048
}

variable "restaurant_ingest_max_concurrency" {
  description = "manifest files[] 병렬 Ingest Task 최대 동시 실행 수"
  type        = number
  default     = 3
}
