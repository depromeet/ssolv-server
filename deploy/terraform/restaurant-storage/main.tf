terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

module "storage" {
  source = "../modules/storage"

  project                                     = var.project
  restaurant_import_bucket_name               = var.restaurant_import_bucket_name
  restaurant_import_raw_expiration_days       = var.restaurant_import_raw_expiration_days
  restaurant_import_processed_expiration_days = var.restaurant_import_processed_expiration_days
}
