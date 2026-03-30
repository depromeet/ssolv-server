terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

module "network" {
  source = "./modules/network"

  project  = var.project
  vpc_cidr = var.vpc_cidr
}

module "compute" {
  source = "./modules/compute"

  project            = var.project
  ami_id             = var.ami_id
  key_name           = var.key_name
  public_key         = var.public_key
  app_instance_count = var.app_instance_count

  vpc_id      = module.network.vpc_id
  subnet_a_id = module.network.public_subnet_a_id
  subnet_b_id = module.network.public_subnet_c_id
  ec2_sg_id   = module.network.ec2_sg_id
}

module "database" {
  source = "./modules/database"

  project           = var.project
  db_name           = var.db_name
  db_username       = var.db_username
  db_password       = var.db_password
  db_instance_class = var.db_instance_class

  private_subnet_ids = module.network.private_subnet_ids
  rds_sg_id          = module.network.rds_sg_id
}

module "dns" {
  source = "./modules/dns"

  project       = var.project
  domain        = var.domain
  instance_a_ip = module.compute.eip_a
  instance_b_ip = module.compute.eip_b
}
