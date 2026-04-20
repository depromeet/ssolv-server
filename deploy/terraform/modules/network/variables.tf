variable "project" {
  description = "프로젝트 이름 (리소스 태그 및 네이밍에 사용)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.1.0.0/16"
}
