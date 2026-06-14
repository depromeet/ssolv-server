variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project" {
  type    = string
  default = "ssolv"
}

variable "restaurant_import_bucket_name" {
  description = "S3 bucket name for restaurant raw/processed import files. Must be globally unique."
  type        = string
}

variable "restaurant_import_raw_expiration_days" {
  description = "Retention period for raw monthly import files."
  type        = number
  default     = 365
}

variable "restaurant_import_processed_expiration_days" {
  description = "Retention period for processed import files."
  type        = number
  default     = 180
}
