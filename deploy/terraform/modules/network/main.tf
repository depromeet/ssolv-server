resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name      = "${var.project}-vpc"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name      = "${var.project}-igw"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Public Subnets (EC2) ─────────────────────────────────────────────────────

resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.1.0.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true

  tags = {
    Name      = "${var.project}-public-a"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_subnet" "public_c" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.1.1.0/24"
  availability_zone       = "ap-northeast-2c"
  map_public_ip_on_launch = true

  tags = {
    Name      = "${var.project}-public-c"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Private Subnets (RDS — 2개 AZ 필수) ─────────────────────────────────────

resource "aws_subnet" "private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.1.128.0/24"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name      = "${var.project}-private-a"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_subnet" "private_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.1.129.0/24"
  availability_zone = "ap-northeast-2b"

  tags = {
    Name      = "${var.project}-private-b"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Route Tables ─────────────────────────────────────────────────────────────

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name      = "${var.project}-public-rt"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_c" {
  subnet_id      = aws_subnet.public_c.id
  route_table_id = aws_route_table.public.id
}

# ─── Security Group: EC2 ──────────────────────────────────────────────────────

resource "aws_security_group" "ec2" {
  name        = "${var.project}-ec2-sg"
  description = "ssolv EC2 instances"
  vpc_id      = aws_vpc.main.id

  # 외부 공개 포트
  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 인스턴스 간 내부 통신 (self)
  ingress {
    description = "App server internal"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    self        = true
  }

  ingress {
    description = "Redis internal"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    self        = true
  }

  ingress {
    description = "OTLP gRPC and HTTP internal"
    from_port   = 4317
    to_port     = 4318
    protocol    = "tcp"
    self        = true
  }

  ingress {
    description = "Docker Registry internal"
    from_port   = 5000
    to_port     = 5000
    protocol    = "tcp"
    self        = true
  }

  ingress {
    description = "Node Exporter internal (Alloy scrape)"
    from_port   = 9100
    to_port     = 9100
    protocol    = "tcp"
    self        = true
  }

  ingress {
    description = "Nginx Exporter internal (Alloy scrape)"
    from_port   = 9113
    to_port     = 9113
    protocol    = "tcp"
    self        = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "${var.project}-ec2-sg"
    Project   = var.project
    ManagedBy = "terraform"
  }
}

# ─── Security Group: RDS ──────────────────────────────────────────────────────

resource "aws_security_group" "rds" {
  name        = "${var.project}-rds-sg"
  description = "ssolv RDS - EC2 access only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL from EC2 only"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "${var.project}-rds-sg"
    Project   = var.project
    ManagedBy = "terraform"
  }
}
