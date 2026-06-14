output "bucket_name" {
  value = data.aws_s3_bucket.restaurant_import.bucket
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

output "event_rule_name" {
  value = aws_cloudwatch_event_rule.manifest_created.name
}

output "batch_task_security_group_id" {
  value = aws_security_group.batch_task.id
}

output "db_endpoint" {
  value = aws_db_instance.restaurant_runtime.address
}

output "db_security_group_id" {
  value = aws_security_group.restaurant_db.id
}
