output "restaurant_import_bucket_name" {
  description = "Batch source file bucket."
  value       = module.storage.restaurant_import_bucket_name
}

output "restaurant_import_bucket_arn" {
  description = "Batch source file bucket ARN."
  value       = module.storage.restaurant_import_bucket_arn
}
