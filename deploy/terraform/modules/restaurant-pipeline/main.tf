locals {
  tags = {
    Project   = var.project
    ManagedBy = "terraform"
    Purpose   = "restaurant-batch-pipeline"
  }
}

resource "aws_s3_bucket" "restaurant_import" {
  bucket        = var.bucket_name
  force_destroy = false

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-import"
  })
}

resource "aws_s3_bucket_public_access_block" "restaurant_import" {
  bucket = aws_s3_bucket.restaurant_import.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "restaurant_import" {
  bucket = aws_s3_bucket.restaurant_import.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "restaurant_import" {
  bucket = aws_s3_bucket.restaurant_import.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "restaurant_import" {
  bucket = aws_s3_bucket.restaurant_import.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "restaurant_import" {
  bucket = aws_s3_bucket.restaurant_import.id

  rule {
    id     = "raw-retention"
    status = "Enabled"

    filter {
      prefix = "raw/"
    }

    expiration {
      days = var.raw_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  rule {
    id     = "reports-retention"
    status = "Enabled"

    filter {
      prefix = "reports/"
    }

    expiration {
      days = var.reports_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

data "aws_iam_policy_document" "restaurant_import_tls_only" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.restaurant_import.arn,
      "${aws_s3_bucket.restaurant_import.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "restaurant_import_tls_only" {
  bucket = aws_s3_bucket.restaurant_import.id
  policy = data.aws_iam_policy_document.restaurant_import_tls_only.json
}

resource "aws_s3_bucket_notification" "restaurant_import_eventbridge" {
  bucket      = aws_s3_bucket.restaurant_import.id
  eventbridge = true
}

resource "aws_ecs_cluster" "restaurant_batch" {
  name = "${var.project}-restaurant-batch"

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-batch"
  })
}

resource "aws_security_group" "batch_task" {
  name        = "${var.project}-restaurant-batch-task-sg"
  description = "Restaurant batch Fargate tasks"
  vpc_id      = var.vpc_id

  tags = merge(local.tags, {
    Name = "${var.project}-restaurant-batch-task-sg"
  })
}

resource "aws_vpc_security_group_egress_rule" "batch_task_all" {
  security_group_id = aws_security_group.batch_task.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_batch" {
  security_group_id            = var.rds_security_group_id
  referenced_security_group_id = aws_security_group.batch_task.id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  description                  = "MySQL from restaurant batch tasks"
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
  name               = "${var.project}-restaurant-batch-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json

  tags = local.tags
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "task" {
  name               = "${var.project}-restaurant-batch-task-role"
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
      aws_s3_bucket.restaurant_import.arn,
      "${aws_s3_bucket.restaurant_import.arn}/*",
    ]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "${var.project}-restaurant-batch-task-policy"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

resource "aws_ecs_task_definition" "batch" {
  family                   = "${var.project}-restaurant-batch"
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
      environment = [
        { name = "RESTAURANT_IMPORT_ENABLED", value = "true" },
        { name = "RESTAURANT_IMPORT_S3_BUCKET", value = aws_s3_bucket.restaurant_import.bucket },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "DEFAULT_SCHEMA", value = var.db_name },
        { name = "DB_USERNAME", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "PROD_DB_ENDPOINT", value = var.db_endpoint },
      ]
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
  name               = "${var.project}-restaurant-pipeline-sfn-role"
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
    resources = ["${aws_s3_bucket.restaurant_import.arn}/manifests/*"]
  }
}

resource "aws_iam_role_policy" "step_functions" {
  name   = "${var.project}-restaurant-pipeline-sfn-policy"
  role   = aws_iam_role.step_functions.id
  policy = data.aws_iam_policy_document.step_functions.json
}

resource "aws_sfn_state_machine" "restaurant_pipeline" {
  name     = "${var.project}-restaurant-import-pipeline"
  role_arn = aws_iam_role.step_functions.arn

  definition = templatefile("${path.module}/state-machine.asl.json", {
    bucket_name            = aws_s3_bucket.restaurant_import.bucket
    cluster_arn            = aws_ecs_cluster.restaurant_batch.arn
    task_definition_arn    = aws_ecs_task_definition.batch.arn
    container_name         = var.batch_container_name
    subnet_ids             = jsonencode(var.subnet_ids)
    security_group_ids     = jsonencode([aws_security_group.batch_task.id])
    assign_public_ip       = var.batch_assign_public_ip ? "ENABLED" : "DISABLED"
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
  name               = "${var.project}-restaurant-pipeline-events-role"
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
  name   = "${var.project}-restaurant-pipeline-events-policy"
  role   = aws_iam_role.eventbridge.id
  policy = data.aws_iam_policy_document.eventbridge.json
}

resource "aws_cloudwatch_event_rule" "manifest_created" {
  name        = "${var.project}-restaurant-manifest-created"
  description = "Starts restaurant import pipeline when a manifest object is created."

  event_pattern = jsonencode({
    source        = ["aws.s3"]
    "detail-type" = ["Object Created"]
    detail = {
      bucket = {
        name = [aws_s3_bucket.restaurant_import.bucket]
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
