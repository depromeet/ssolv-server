output "bucket_name" {
  value = aws_s3_bucket.restaurant_import.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.restaurant_import.arn
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.restaurant_batch.name
}

output "ecs_cluster_arn" {
  value = aws_ecs_cluster.restaurant_batch.arn
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.batch.arn
}

output "state_machine_arn" {
  value = aws_sfn_state_machine.restaurant_pipeline.arn
}

output "batch_task_security_group_id" {
  value = aws_security_group.batch_task.id
}
