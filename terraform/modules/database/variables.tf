variable "project" {
  type = string
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

variable "private_subnet_ids" {
  type = list(string)
}

variable "rds_sg_id" {
  type = string
}
