variable "project" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "bucket_name" {
  description = "S3 bucket name for restaurant import CSV and manifest files."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Subnets for one-off Fargate batch tasks."
  type        = list(string)
}

variable "rds_security_group_id" {
  type = string
}

variable "batch_container_image" {
  description = "Container image URI for ssolv-batch."
  type        = string
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

variable "batch_assign_public_ip" {
  type    = bool
  default = true
}

variable "ingest_max_concurrency" {
  type    = number
  default = 3
}

variable "raw_expiration_days" {
  type    = number
  default = 365
}

variable "reports_expiration_days" {
  type    = number
  default = 180
}

variable "db_endpoint" {
  type = string
}

variable "db_name" {
  type = string
}

variable "db_username" {
  type      = string
  sensitive = true
}

variable "db_password" {
  type      = string
  sensitive = true
}
