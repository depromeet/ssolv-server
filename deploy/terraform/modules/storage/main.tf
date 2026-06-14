resource "aws_s3_bucket" "restaurant_import" {
  bucket        = var.restaurant_import_bucket_name
  force_destroy = false

  tags = {
    Name      = "${var.project}-restaurant-import"
    Project   = var.project
    ManagedBy = "terraform"
    Purpose   = "restaurant-batch-import"
  }
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
      days = var.restaurant_import_raw_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  rule {
    id     = "processed-retention"
    status = "Enabled"

    filter {
      prefix = "processed/"
    }

    expiration {
      days = var.restaurant_import_processed_expiration_days
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
