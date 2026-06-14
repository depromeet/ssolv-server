output "restaurant_import_bucket_name" {
  value = aws_s3_bucket.restaurant_import.bucket
}

output "restaurant_import_bucket_arn" {
  value = aws_s3_bucket.restaurant_import.arn
}
