resource "aws_key_pair" "ssolv" {
  key_name   = var.key_name
  public_key = var.public_key

  tags = {
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Instance A (t3.micro) — nginx + app-server ───────────────────────────────
# app_instance_count = 1이면 생성 안 함 (단일 서버 모드 = B만 운영)

resource "aws_instance" "app_a" {
  count = var.app_instance_count >= 2 ? 1 : 0

  ami                    = var.ami_id
  instance_type          = var.instance_type_a
  subnet_id              = var.subnet_a_id
  vpc_security_group_ids = [var.ec2_sg_id]
  key_name               = aws_key_pair.ssolv.key_name

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name      = "${var.project}-app-a"
    Role      = "app"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Instance B (t3.small) — nginx + app-server + redis + registry + monitoring ─

resource "aws_instance" "app_b" {
  ami                    = var.ami_id
  instance_type          = var.instance_type_b
  subnet_id              = var.subnet_b_id
  vpc_security_group_ids = [var.ec2_sg_id]
  key_name               = aws_key_pair.ssolv.key_name

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name      = "${var.project}-app-b"
    Role      = "app-infra"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Elastic IPs ──────────────────────────────────────────────────────────────

resource "aws_eip" "app_a" {
  count  = var.app_instance_count >= 2 ? 1 : 0
  domain = "vpc"

  tags = {
    Name      = "${var.project}-eip-a"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_eip" "app_b" {
  domain = "vpc"

  tags = {
    Name      = "${var.project}-eip-b"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_eip_association" "app_a" {
  count         = var.app_instance_count >= 2 ? 1 : 0
  instance_id   = aws_instance.app_a[0].id
  allocation_id = aws_eip.app_a[0].id
}

resource "aws_eip_association" "app_b" {
  instance_id   = aws_instance.app_b.id
  allocation_id = aws_eip.app_b.id
}
