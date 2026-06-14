terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.100"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

locals {
  tags = {
    Project   = var.project
    ManagedBy = "terraform"
    Purpose   = "restaurant-batch-pipeline-runtime"
  }
}

data "aws_s3_bucket" "restaurant_import" {
  bucket = var.bucket_name
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

resource "aws_db_subnet_group" "restaurant_runtime" {
  name       = "${var.project}-restaurant-runtime-db-subnet-group"
  subnet_ids = data.aws_subnets.default.ids

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-runtime-db-subnet-group"
  })
}

resource "aws_s3_bucket_notification" "restaurant_import_eventbridge" {
  bucket      = data.aws_s3_bucket.restaurant_import.id
  eventbridge = true
}

resource "aws_cloudwatch_log_group" "batch" {
  name              = "/ecs/${var.project}-restaurant-batch-runtime"
  retention_in_days = 14

  tags = local.tags
}

resource "aws_ecs_cluster" "restaurant_batch" {
  name = "${var.project}-restaurant-batch-runtime"

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-batch-runtime"
  })
}

resource "aws_security_group" "batch_task" {
  name        = "${var.project}-restaurant-batch-runtime-sg"
  description = "Restaurant batch Fargate runtime tasks"
  vpc_id      = data.aws_vpc.default.id

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-batch-runtime-sg"
  })
}

resource "aws_vpc_security_group_egress_rule" "batch_task_all" {
  security_group_id = aws_security_group.batch_task.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "restaurant_db" {
  name        = "${var.project}-restaurant-runtime-db-sg"
  description = "Restaurant batch runtime MySQL"
  vpc_id      = data.aws_vpc.default.id

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-runtime-db-sg"
  })
}

resource "aws_vpc_security_group_ingress_rule" "restaurant_db_from_batch" {
  security_group_id            = aws_security_group.restaurant_db.id
  referenced_security_group_id = aws_security_group.batch_task.id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  description                  = "MySQL from restaurant batch runtime tasks"
}

resource "aws_db_instance" "restaurant_runtime" {
  identifier             = "${var.project}-restaurant-runtime-mysql"
  allocated_storage      = 20
  max_allocated_storage  = 100
  engine                 = "mysql"
  engine_version         = "8.0.43"
  instance_class         = var.db_instance_class
  db_name                = var.db_name
  username               = var.db_username
  password               = var.db_password
  db_subnet_group_name   = aws_db_subnet_group.restaurant_runtime.name
  vpc_security_group_ids = [aws_security_group.restaurant_db.id]
  publicly_accessible    = false
  storage_encrypted      = true
  skip_final_snapshot    = true
  deletion_protection    = false
  apply_immediately      = true

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-runtime-mysql"
  })
}

data "aws_iam_policy_document" "ecs_task_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task_execution" {
  name               = "${var.project}-restaurant-batch-runtime-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json

  tags = local.tags
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "task" {
  name               = "${var.project}-restaurant-batch-runtime-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json

  tags = local.tags
}

data "aws_iam_policy_document" "task" {
  statement {
    actions = [
      "s3:GetObject",
      "s3:ListBucket",
      "s3:PutObject",
    ]

    resources = [
      data.aws_s3_bucket.restaurant_import.arn,
      "${data.aws_s3_bucket.restaurant_import.arn}/*",
    ]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "${var.project}-restaurant-batch-runtime-task-policy"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

resource "aws_ecs_task_definition" "batch" {
  family                   = "${var.project}-restaurant-batch-runtime"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.batch_cpu)
  memory                   = tostring(var.batch_memory)
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = var.batch_container_name
      image     = var.batch_container_image
      essential = true
      dependsOn = [
        {
          containerName = "redis"
          condition     = "HEALTHY"
        }
      ]
      environment = [
        { name = "RESTAURANT_IMPORT_ENABLED", value = "true" },
        { name = "RESTAURANT_IMPORT_ENRICHMENT_PUBLISH_ENABLED", value = "false" },
        { name = "RESTAURANT_ENRICHMENT_ENABLED", value = "false" },
        { name = "SPRING_DATA_REDIS_REPOSITORIES_ENABLED", value = "false" },
        { name = "RESTAURANT_IMPORT_S3_BUCKET", value = data.aws_s3_bucket.restaurant_import.bucket },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "REDIS_HOST", value = "127.0.0.1" },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:mysql://${aws_db_instance.restaurant_runtime.address}:${aws_db_instance.restaurant_runtime.port}/${var.db_name}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" },
        { name = "DEFAULT_SCHEMA", value = var.db_name },
        { name = "DB_USERNAME", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "PROD_DB_ENDPOINT", value = aws_db_instance.restaurant_runtime.address },
      ],
      logConfiguration = {
        logDriver = "awslogs",
        options = {
          awslogs-group         = aws_cloudwatch_log_group.batch.name,
          awslogs-region        = var.aws_region,
          awslogs-stream-prefix = "ssolv-batch"
        }
      }
    },
    {
      name      = "redis"
      image     = "public.ecr.aws/docker/library/redis:7-alpine"
      essential = true
      healthCheck = {
        command     = ["CMD-SHELL", "redis-cli ping | grep PONG"]
        interval    = 5
        timeout     = 3
        retries     = 5
        startPeriod = 5
      }
      logConfiguration = {
        logDriver = "awslogs",
        options = {
          awslogs-group         = aws_cloudwatch_log_group.batch.name,
          awslogs-region        = var.aws_region,
          awslogs-stream-prefix = "redis"
        }
      }
    }
  ])

  tags = local.tags
}

data "aws_iam_policy_document" "step_functions_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "step_functions" {
  name               = "${var.project}-restaurant-pipeline-runtime-sfn-role"
  assume_role_policy = data.aws_iam_policy_document.step_functions_assume_role.json

  tags = local.tags
}

data "aws_iam_policy_document" "step_functions" {
  statement {
    actions = [
      "ecs:RunTask",
      "ecs:StopTask",
      "ecs:DescribeTasks",
    ]

    resources = ["*"]
  }

  statement {
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.task_execution.arn,
      aws_iam_role.task.arn,
    ]
  }

  statement {
    actions = [
      "events:PutTargets",
      "events:PutRule",
      "events:DescribeRule",
    ]

    resources = ["*"]
  }

  statement {
    actions   = ["s3:GetObject"]
    resources = ["${data.aws_s3_bucket.restaurant_import.arn}/manifests/*"]
  }
}

resource "aws_iam_role_policy" "step_functions" {
  name   = "${var.project}-restaurant-pipeline-runtime-sfn-policy"
  role   = aws_iam_role.step_functions.id
  policy = data.aws_iam_policy_document.step_functions.json
}

resource "aws_sfn_state_machine" "restaurant_pipeline" {
  name     = "${var.project}-restaurant-import-pipeline-runtime"
  role_arn = aws_iam_role.step_functions.arn

  definition = templatefile("${path.module}/../modules/restaurant-pipeline/state-machine.asl.json", {
    bucket_name            = data.aws_s3_bucket.restaurant_import.bucket
    cluster_arn            = aws_ecs_cluster.restaurant_batch.arn
    task_definition_arn    = aws_ecs_task_definition.batch.arn
    container_name         = var.batch_container_name
    subnet_ids             = jsonencode(data.aws_subnets.default.ids)
    security_group_ids     = jsonencode([aws_security_group.batch_task.id])
    assign_public_ip       = "ENABLED"
    ingest_max_concurrency = var.ingest_max_concurrency
  })

  tags = local.tags
}

data "aws_iam_policy_document" "eventbridge_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eventbridge" {
  name               = "${var.project}-restaurant-pipeline-runtime-events-role"
  assume_role_policy = data.aws_iam_policy_document.eventbridge_assume_role.json

  tags = local.tags
}

data "aws_iam_policy_document" "eventbridge" {
  statement {
    actions   = ["states:StartExecution"]
    resources = [aws_sfn_state_machine.restaurant_pipeline.arn]
  }
}

resource "aws_iam_role_policy" "eventbridge" {
  name   = "${var.project}-restaurant-pipeline-runtime-events-policy"
  role   = aws_iam_role.eventbridge.id
  policy = data.aws_iam_policy_document.eventbridge.json
}

resource "aws_cloudwatch_event_rule" "manifest_created" {
  name        = "${var.project}-restaurant-manifest-created-runtime"
  description = "Starts restaurant import pipeline when a manifest object is created."

  event_pattern = jsonencode({
    source        = ["aws.s3"]
    "detail-type" = ["Object Created"]
    detail = {
      bucket = {
        name = [data.aws_s3_bucket.restaurant_import.bucket]
      }
      object = {
        key = [{ prefix = "manifests/" }]
      }
    }
  })

  tags = local.tags
}

resource "aws_cloudwatch_event_target" "manifest_created" {
  rule     = aws_cloudwatch_event_rule.manifest_created.name
  arn      = aws_sfn_state_machine.restaurant_pipeline.arn
  role_arn = aws_iam_role.eventbridge.arn
}
