variable "project" {
  type = string
}

variable "domain" {
  description = "루트 도메인 (예: ssolv.site)"
  type        = string
}

variable "instance_a_ip" {
  description = "인스턴스 A EIP"
  type        = string
}

variable "instance_b_ip" {
  description = "인스턴스 B EIP"
  type        = string
}
