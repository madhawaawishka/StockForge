output "api_url" {
  description = "Public URL of the API (the gateway behind the load balancer)."
  value       = "${var.certificate_arn == "" ? "http" : "https"}://${aws_lb.this.dns_name}"
}

output "ecr_repositories" {
  description = "Where CI pushes each service image."
  value       = { for name, repo in aws_ecr_repository.service : name => repo.repository_url }
}

output "database_endpoint" {
  value = aws_db_instance.this.address
}

output "database_admin_secret_arn" {
  description = "RDS-managed admin credentials, needed once for the schema bootstrap."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "service_log_groups" {
  value = { for name, svc in module.service : name => svc.log_group_name }
}
