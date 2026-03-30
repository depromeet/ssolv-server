resource "aws_db_subnet_group" "main" {
  name       = "${var.project}-db-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name      = "${var.project}-db-subnet-group"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_db_instance" "main" {
  identifier        = "${var.project}-mysql"
  engine            = "mysql"
  engine_version    = "8.0.43"
  instance_class    = var.db_instance_class
  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [var.rds_sg_id]

  publicly_accessible     = false
  deletion_protection     = true
  backup_retention_period = 0  # TODO: 결제 정보 등록 후 7로 변경
  skip_final_snapshot     = false
  final_snapshot_identifier = "${var.project}-mysql-final"

  tags = {
    Name      = "${var.project}-mysql"
    Project   = var.project
    ManagedBy = "terraform"
  }
}
