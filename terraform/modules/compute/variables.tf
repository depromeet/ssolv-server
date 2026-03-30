variable "project" {
  type = string
}

variable "ami_id" {
  description = "Ubuntu 22.04 LTS x86_64 AMI ID"
  type        = string
}

variable "key_name" {
  description = "EC2 키페어 이름"
  type        = string
}

variable "public_key" {
  description = "SSH 공개키 (depromeet-secret.pem에서 추출)"
  type        = string
  sensitive   = true
}

variable "app_instance_count" {
  description = "앱 서버 수 (1=B만, 2=A+B 멀티서버)"
  type        = number
  default     = 2
}

variable "vpc_id" {
  type = string
}

variable "subnet_a_id" {
  description = "인스턴스 A용 퍼블릭 서브넷 (ap-northeast-2a)"
  type        = string
}

variable "subnet_b_id" {
  description = "인스턴스 B용 퍼블릭 서브넷 (ap-northeast-2c)"
  type        = string
}

variable "ec2_sg_id" {
  type = string
}
