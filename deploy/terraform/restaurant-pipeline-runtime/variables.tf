variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project" {
  type    = string
  default = "ssolv"
}

variable "bucket_name" {
  type = string
}

variable "batch_container_image" {
  type = string
}

variable "batch_container_name" {
  type    = string
  default = "ssolv-batch"
}

variable "batch_cpu" {
  type    = number
  default = 1024
}

variable "batch_memory" {
  type    = number
  default = 2048
}

variable "ingest_max_concurrency" {
  type    = number
  default = 3
}

variable "db_name" {
  type    = string
  default = "ssolv"
}

variable "db_username" {
  type      = string
  sensitive = true
  default   = "ssolv"
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "db_instance_class" {
  type    = string
  default = "db.t3.micro"
}
